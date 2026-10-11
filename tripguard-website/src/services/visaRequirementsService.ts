import type {
  ApplicantProfile,
  ApplicantScopeCheck,
  ConditionalDocument,
  DocumentRequirement,
  ExtensionRule,
  FinancialRequirement,
  SourceRecord,
  VisaRequirementsDataset,
  VisaType,
} from "../types/visa";

/** Default runtime location of the non-sensitive requirements dataset. */
export const DEFAULT_DATASET_URL = "/data/visa-requirements.json";

/**
 * Categories the agency console is intended to support. Any other category in
 * the dataset is surfaced but marked unsupported so it cannot be reviewed as if
 * it were a supported product.
 */
export const SUPPORTED_CATEGORY_IDS = [
  "tourist_30_day_single_entry",
  "tourist_60_day_single_entry",
  "tourist_5_year_multiple_entry",
] as const;

export type DatasetErrorCode =
  | "NOT_FOUND"
  | "MALFORMED_JSON"
  | "SCHEMA_MISMATCH"
  | "NETWORK";

export class VisaRequirementsError extends Error {
  readonly code: DatasetErrorCode;
  readonly detail?: string;

  constructor(code: DatasetErrorCode, message: string, detail?: string) {
    super(message);
    this.name = "VisaRequirementsError";
    this.code = code;
    this.detail = detail;
  }
}

export interface ValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

/**
 * Structural validation of a raw parsed dataset. Never mutates the input.
 * Returns every problem found rather than throwing on the first one.
 */
export function validateVisaRequirements(raw: unknown): ValidationResult {
  const errors: string[] = [];
  const warnings: string[] = [];

  if (!isObject(raw)) {
    return { valid: false, errors: ["Dataset root must be a JSON object."], warnings };
  }

  const requiredStrings = [
    "dataset_id",
    "schema_version",
    "destination_country",
    "destination_emirate",
  ];
  for (const key of requiredStrings) {
    if (!isNonEmptyString(raw[key])) {
      errors.push(`Missing or invalid required field "${key}".`);
    }
  }

  if (!Array.isArray(raw.visa_types)) {
    errors.push('Missing or invalid required array "visa_types".');
  } else if (raw.visa_types.length === 0) {
    errors.push('"visa_types" must contain at least one visa category.');
  }

  if (!Array.isArray(raw.sources)) {
    warnings.push('Missing "sources" array; source details will be unavailable.');
  }

  if (!isObject(raw.supported_applicant_scope)) {
    warnings.push(
      'Missing "supported_applicant_scope"; applicant scope checks will be limited.',
    );
  }

  if (!isObject(raw.verification_policy)) {
    warnings.push(
      'Missing "verification_policy"; automated decision guardrails may be incomplete.',
    );
  }

  if (Array.isArray(raw.visa_types)) {
    const seenIds = new Set<string>();
    raw.visa_types.forEach((visa, index) => {
      const label = `visa_types[${index}]`;
      if (!isObject(visa)) {
        errors.push(`${label} must be an object.`);
        return;
      }
      if (!isNonEmptyString(visa.id)) {
        errors.push(`${label}.id is missing or invalid.`);
      } else if (seenIds.has(visa.id)) {
        errors.push(`${label}.id "${visa.id}" is duplicated.`);
      } else {
        seenIds.add(visa.id);
      }
      if (!isNonEmptyString(visa.display_name)) {
        errors.push(`${label}.display_name is missing or invalid.`);
      }
      if (!Array.isArray(visa.documents)) {
        errors.push(`${label}.documents must be an array.`);
      } else {
        visa.documents.forEach((doc: unknown, docIndex: number) => {
          const docLabel = `${label}.documents[${docIndex}]`;
          if (!isObject(doc)) {
            errors.push(`${docLabel} must be an object.`);
            return;
          }
          if (!isNonEmptyString(doc.id)) {
            errors.push(`${docLabel}.id is missing or invalid.`);
          }
          if (!isNonEmptyString(doc.name)) {
            errors.push(`${docLabel}.name is missing or invalid.`);
          }
          if (typeof doc.mandatory !== "boolean") {
            warnings.push(`${docLabel}.mandatory is not a boolean.`);
          }
          if (!isObject(doc.applicability)) {
            warnings.push(`${docLabel}.applicability is missing.`);
          }
        });
      }
    });
  }

  return { valid: errors.length === 0, errors, warnings };
}

export interface LoadOptions {
  /** Override the fetch implementation (used by tests). */
  fetcher?: typeof fetch;
  /** Override the dataset URL. */
  url?: string;
  /** Skip network entirely and validate an in-memory object. */
  data?: unknown;
}

function normalizeDataset(data: VisaRequirementsDataset): VisaRequirementsDataset {
  return {
    ...data,
    visa_types: data.visa_types.map((visa) => ({
      ...visa,
      documents: visa.documents ?? [],
      conditional_documents: visa.conditional_documents ?? [],
      financial_requirements: visa.financial_requirements ?? [],
      extension_rules: visa.extension_rules ?? [],
      restrictions: visa.restrictions ?? [],
      fees: visa.fees ?? [],
    })),
  };
}

/**
 * Load and validate the visa-requirements dataset.
 *
 * Errors are surfaced as {@link VisaRequirementsError} so the UI can explain
 * exactly what went wrong (missing file, malformed JSON, or schema mismatch).
 */
export async function loadVisaRequirements(
  options: LoadOptions = {},
): Promise<VisaRequirementsDataset> {
  let raw: unknown;

  if (options.data !== undefined) {
    raw = options.data;
  } else {
    const fetcher = options.fetcher ?? fetch;
    const url = options.url ?? DEFAULT_DATASET_URL;

    let response: Response;
    try {
      response = await fetcher(url);
    } catch (error) {
      throw new VisaRequirementsError(
        "NETWORK",
        "Unable to reach the visa requirements file.",
        error instanceof Error ? error.message : String(error),
      );
    }

    if (response.status === 404) {
      throw new VisaRequirementsError(
        "NOT_FOUND",
        `Visa requirements file was not found at "${url}".`,
      );
    }

    if (!response.ok) {
      throw new VisaRequirementsError(
        "NETWORK",
        `Unable to load visa requirements (HTTP ${response.status}).`,
      );
    }

    const text = await response.text();
    try {
      raw = JSON.parse(text);
    } catch (error) {
      throw new VisaRequirementsError(
        "MALFORMED_JSON",
        "The visa requirements file is not valid JSON.",
        error instanceof Error ? error.message : String(error),
      );
    }
  }

  const validation = validateVisaRequirements(raw);
  if (!validation.valid) {
    throw new VisaRequirementsError(
      "SCHEMA_MISMATCH",
      "The visa requirements file does not match the expected schema.",
      validation.errors.join(" "),
    );
  }

  return normalizeDataset(raw as VisaRequirementsDataset);
}

export interface VisaCategorySummary {
  id: string;
  displayName: string;
  officialName: string;
  category: string;
  entryType: string;
  productStatus: string;
  supported: boolean;
  humanReviewRequired: boolean;
  eligibilityVerified: boolean;
  documentCount: number;
  mandatoryDocumentCount: number;
}

export function isSupportedCategory(categoryId: string): boolean {
  return (SUPPORTED_CATEGORY_IDS as readonly string[]).includes(categoryId);
}

export function getVisaCategories(
  dataset: VisaRequirementsDataset,
): VisaCategorySummary[] {
  return dataset.visa_types.map((visa) => ({
    id: visa.id,
    displayName: visa.display_name,
    officialName: visa.official_name,
    category: visa.category,
    entryType: visa.entry_type,
    productStatus: visa.product_status,
    supported: isSupportedCategory(visa.id),
    humanReviewRequired: visa.human_review_required,
    eligibilityVerified: visa.eligibility?.verified ?? false,
    documentCount: visa.documents.length,
    mandatoryDocumentCount: visa.documents.filter((d) => d.mandatory).length,
  }));
}

export function getVisaType(
  dataset: VisaRequirementsDataset,
  categoryId: string,
): VisaType | undefined {
  return dataset.visa_types.find((visa) => visa.id === categoryId);
}

export interface UnverifiedItem {
  id: string;
  label: string;
  detail: string;
  sourceIds: string[];
}

export interface CategoryRequirements {
  visaType: VisaType;
  supported: boolean;
  documents: DocumentRequirement[];
  conditionalDocuments: ConditionalDocument[];
  financialRequirements: FinancialRequirement[];
  extensionRules: ExtensionRule[];
  sources: SourceRecord[];
  unverifiedItems: UnverifiedItem[];
}

/**
 * Collect every aspect of a category that the dataset itself flags as
 * unverified, conditional, or route-specific. The UI must show these rather
 * than silently treating the category as ready.
 */
export function collectUnverifiedItems(visa: VisaType): UnverifiedItem[] {
  const items: UnverifiedItem[] = [];

  if (visa.product_status && !/^verified|active|confirmed$/i.test(visa.product_status)) {
    items.push({
      id: `product_status:${visa.id}`,
      label: "Product status not confirmed",
      detail: `Dataset product_status is "${visa.product_status}".`,
      sourceIds: visa.sources,
    });
  }

  if (visa.eligibility && visa.eligibility.verified === false) {
    items.push({
      id: `eligibility:${visa.id}`,
      label: "Applicant eligibility unverified",
      detail: visa.eligibility.conditions.join(" ") || "Eligibility is not verified for this category.",
      sourceIds: visa.eligibility.source_ids,
    });
  }

  for (const financial of visa.financial_requirements) {
    const machineBlocked = financial.machine_check_allowed === false;
    const statusUnverified = !/^verified$/i.test(financial.requirement_status);
    if (machineBlocked || statusUnverified) {
      items.push({
        id: `financial:${financial.id}`,
        label: "Financial evidence requires manual review",
        detail:
          financial.evidence_notes.join(" ") ||
          financial.requirement_status ||
          "Financial threshold wording differs between official sources.",
        sourceIds: financial.source_ids,
      });
    }
  }

  for (const rule of visa.extension_rules) {
    if (!/^verified$/i.test(rule.status)) {
      items.push({
        id: `extension:${rule.status}:${visa.id}`,
        label: "Extension rule unverified",
        detail: rule.description,
        sourceIds: rule.source_ids,
      });
    }
  }

  for (const doc of visa.documents) {
    for (const rule of doc.verification_rules) {
      const missingParams =
        rule.parameters?.minimum_validity_days === null &&
        rule.rule_type !== "human_review";
      if (rule.human_review_required || missingParams) {
        items.push({
          id: `doc_rule:${doc.id}:${rule.id}`,
          label: `Unverified rule: ${doc.name}`,
          detail: rule.description,
          sourceIds: rule.source_ids,
        });
      }
    }
    if (doc.human_review_required && doc.verification_rules.length === 0) {
      items.push({
        id: `doc_review:${doc.id}`,
        label: `Human review required: ${doc.name}`,
        detail:
          doc.evidence_notes.join(" ") ||
          doc.specifications.other_conditions?.join(" ") ||
          "Dataset marks this document as requiring human review.",
        sourceIds: doc.source_ids,
      });
    }
    if (doc.route_specific_requirements?.length) {
      items.push({
        id: `route_specific:${doc.id}`,
        label: `Route-specific requirement: ${doc.name}`,
        detail: doc.route_specific_requirements
          .map((r) => `${r.route}: ${r.condition}`)
          .join(" "),
        sourceIds: doc.source_ids,
      });
    }
  }

  for (const doc of visa.conditional_documents) {
    if (doc.human_review_required) {
      items.push({
        id: `conditional:${doc.id}`,
        label: `Conditional requirement: ${doc.name}`,
        detail: doc.applicability.conditions.join(" "),
        sourceIds: doc.source_ids,
      });
    }
  }

  return items;
}

export function getRequirementsForCategory(
  dataset: VisaRequirementsDataset,
  categoryId: string,
): CategoryRequirements | undefined {
  const visaType = getVisaType(dataset, categoryId);
  if (!visaType) return undefined;

  return {
    visaType,
    supported: isSupportedCategory(categoryId),
    documents: visaType.documents,
    conditionalDocuments: visaType.conditional_documents,
    financialRequirements: visaType.financial_requirements,
    extensionRules: visaType.extension_rules,
    sources: getSourcesForVisaType(dataset, visaType),
    unverifiedItems: collectUnverifiedItems(visaType),
  };
}

export function getSourcesForVisaType(
  dataset: VisaRequirementsDataset,
  visaType: VisaType,
): SourceRecord[] {
  const ids = new Set(visaType.sources);
  return (dataset.sources ?? []).filter((source) => ids.has(source.id));
}

/**
 * Compare an applicant profile to the dataset's supported scope. Unsupported
 * combinations must be routed for manual review, never auto-approved.
 */
export function checkApplicantScope(
  dataset: VisaRequirementsDataset,
  profile: Pick<ApplicantProfile, "nationality" | "passport_type" | "purpose">,
): ApplicantScopeCheck {
  const scope = dataset.supported_applicant_scope;
  if (!scope) {
    return {
      supported: false,
      reason: "Dataset does not declare a supported applicant scope.",
    };
  }

  const matches =
    profile.nationality.trim().toLowerCase() === scope.nationality.toLowerCase() &&
    profile.passport_type.trim().toLowerCase() ===
      scope.passport_type.toLowerCase() &&
    profile.purpose.trim().toLowerCase() === scope.purpose.toLowerCase();

  if (matches) {
    return { supported: true, reason: scope.scope_enforcement };
  }

  return {
    supported: false,
    reason:
      `Applicant (${profile.nationality} / ${profile.passport_type} / ${profile.purpose}) ` +
      `is outside the supported scope (${scope.nationality} / ${scope.passport_type} / ${scope.purpose}). ` +
      scope.scope_enforcement,
  };
}
