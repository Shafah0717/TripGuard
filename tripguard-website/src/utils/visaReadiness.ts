import type {
  ApplicantScopeCheck,
  DocumentRequirement,
  VerificationRule,
  VisaRequirementsDataset,
} from "../types/visa";
import type { UploadedDocument, VisaApplication } from "../types/application";
import {
  checkApplicantScope,
  getRequirementsForCategory,
  type CategoryRequirements,
} from "../services/visaRequirementsService";

/**
 * Consistent readiness statuses used across requirement and check results.
 * `RULE_UNVERIFIED` and `MANUAL_REVIEW_REQUIRED` can never be treated as ready.
 */
export type ReadinessStatus =
  | "SATISFIED"
  | "MISSING"
  | "POTENTIAL_ISSUE"
  | "MANUAL_REVIEW_REQUIRED"
  | "NOT_YET_CHECKED"
  | "RULE_UNVERIFIED";

export const READINESS_STATUSES: ReadinessStatus[] = [
  "SATISFIED",
  "MISSING",
  "POTENTIAL_ISSUE",
  "MANUAL_REVIEW_REQUIRED",
  "NOT_YET_CHECKED",
  "RULE_UNVERIFIED",
];

export interface RequirementResult {
  requirementId: string;
  requirementName: string;
  mandatory: boolean;
  status: ReadinessStatus;
  reason: string;
  uploadedDocuments: UploadedDocument[];
  unverifiedRuleIds: string[];
  sourceIds: string[];
}

export interface CheckResult {
  id: string;
  label: string;
  status: ReadinessStatus;
  detail: string;
}

export interface ConditionalNote {
  requirementId: string;
  requirementName: string;
  applicability: string;
  conditions: string[];
  applicable: boolean;
  reason: string;
}

export interface ReadinessReport {
  applicationId: string;
  visaCategoryId: string;
  visaCategoryName: string;
  categorySupported: boolean;
  scope: ApplicantScopeCheck;
  overallStatus: ReadinessStatus;
  readyForSubmission: boolean;
  allMandatoryDocumentsSatisfied: boolean;
  results: RequirementResult[];
  checks: CheckResult[];
  notApplicableConditional: ConditionalNote[];
  missingDocuments: RequirementResult[];
  potentialIssues: RequirementResult[];
  manualReviewItems: RequirementResult[];
  unverifiedRequirements: RequirementResult[];
  notYetChecked: RequirementResult[];
  satisfied: RequirementResult[];
  unverifiedCategoryItems: CategoryRequirements["unverifiedItems"];
  recommendedActions: string[];
  generatedAt: string;
  disclaimer: string;
}

const MS_PER_DAY = 1000 * 60 * 60 * 24;

function parseDate(value: string | null | undefined): Date | null {
  if (!value) return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

/**
 * A rule is "unverifiable" when the dataset does not give the machine a usable
 * threshold, or explicitly requires human review before an outcome is decided.
 */
export function isRuleUnverifiable(rule: VerificationRule): boolean {
  if (rule.rule_type === "human_review") return true;
  if (rule.human_review_required) return true;
  if (
    Object.prototype.hasOwnProperty.call(rule.parameters ?? {}, "minimum_validity_days") &&
    (rule.parameters.minimum_validity_days === null ||
      rule.parameters.minimum_validity_days === undefined)
  ) {
    return true;
  }
  if (
    Object.prototype.hasOwnProperty.call(rule.parameters ?? {}, "automatic_decision") &&
    rule.parameters.automatic_decision === false
  ) {
    return true;
  }
  return false;
}

function evaluateDocumentRequirement(
  requirement: DocumentRequirement,
  uploads: UploadedDocument[],
): RequirementResult {
  const unverifiedRules = requirement.verification_rules.filter(isRuleUnverifiable);
  const unverifiedRuleIds = unverifiedRules.map((rule) => rule.id);
  const base = {
    requirementId: requirement.id,
    requirementName: requirement.name,
    mandatory: requirement.mandatory,
    uploadedDocuments: uploads,
    unverifiedRuleIds,
    sourceIds: requirement.source_ids,
  };

  if (uploads.length === 0) {
    if (!requirement.mandatory) {
      return {
        ...base,
        status: "NOT_YET_CHECKED",
        reason: "Optional requirement; no document uploaded.",
      };
    }
    return {
      ...base,
      status: "MISSING",
      reason: "No document uploaded for this required document.",
    };
  }

  const reviewed = uploads.filter((doc) => doc.reviewStatus === "REVIEWED_OK");
  const needsReupload = uploads.filter(
    (doc) => doc.reviewStatus === "NEEDS_REUPLOAD",
  );

  if (needsReupload.length > 0) {
    return {
      ...base,
      status: "POTENTIAL_ISSUE",
      reason: "A reviewer marked an uploaded document as needing re-upload.",
    };
  }

  if (reviewed.length === 0) {
    return {
      ...base,
      status: "NOT_YET_CHECKED",
      reason:
        "A document was uploaded but no reviewer has verified it yet. An upload alone does not satisfy the requirement.",
    };
  }

  if (unverifiedRuleIds.length > 0) {
    return {
      ...base,
      status: "RULE_UNVERIFIED",
      reason:
        "Document present and reviewed, but at least one rule for this document is unverified and needs manual confirmation: " +
        unverifiedRules.map((rule) => rule.description).join(" "),
    };
  }

  return {
    ...base,
    status: "SATISFIED",
    reason: "Document uploaded and reviewed, with no unverified rule attached.",
  };
}

function evaluateConditionalDocuments(
  categoryRequirements: CategoryRequirements,
  application: VisaApplication,
): { notes: ConditionalNote[]; results: RequirementResult[] } {
  const notes: ConditionalNote[] = [];
  const results: RequirementResult[] = [];

  for (const conditional of categoryRequirements.conditionalDocuments) {
    const scope = conditional.applicability.scope;
    const appliesToSpecificNationalities = scope.includes("specific_nationalities");
    const isRouteSpecific =
      scope.includes("route") || scope.includes("five_year") || scope.includes("icp") ||
      /route_specific/.test(conditional.requirement_status);

    if (appliesToSpecificNationalities) {
      notes.push({
        requirementId: conditional.id,
        requirementName: conditional.name,
        applicability: scope,
        conditions: conditional.applicability.conditions,
        applicable: false,
        reason:
          "Not applicable to the current Indian ordinary-passport scope. Informational only.",
      });
      continue;
    }

    if (isRouteSpecific) {
      notes.push({
        requirementId: conditional.id,
        requirementName: conditional.name,
        applicability: scope,
        conditions: conditional.applicability.conditions,
        applicable: true,
        reason:
          "Route-specific requirement. The application does not record an issuing route, so this must be confirmed manually.",
      });
      results.push({
        requirementId: conditional.id,
        requirementName: conditional.name,
        mandatory: false,
        status: "MANUAL_REVIEW_REQUIRED",
        reason:
          "Route-specific requirement with unconfirmed applicability. Confirm the issuing route before relying on this.",
        uploadedDocuments: application.documents.filter(
          (doc) => doc.requirementId === conditional.id,
        ),
        unverifiedRuleIds: [],
        sourceIds: conditional.source_ids,
      });
      continue;
    }

    notes.push({
      requirementId: conditional.id,
      requirementName: conditional.name,
      applicability: scope,
      conditions: conditional.applicability.conditions,
      applicable: conditional.human_review_required,
      reason: conditional.human_review_required
        ? "Conditional requirement needs manual confirmation for this applicant."
        : "Conditional requirement recorded for completeness.",
    });
  }

  return { notes, results };
}

function evaluatePassportValidity(
  categoryRequirements: CategoryRequirements,
  application: VisaApplication,
): CheckResult {
  const passport = categoryRequirements.documents.find(
    (doc) => doc.id === "passport_copy" || doc.name.toLowerCase().includes("passport"),
  );
  const rule = passport?.verification_rules.find(
    (item) => item.rule_type === "passport_expiry_check",
  );

  if (!rule) {
    return {
      id: "passport_validity",
      label: "Passport validity",
      status: "NOT_YET_CHECKED",
      detail: "No configured passport validity rule was found for this category.",
    };
  }

  const minimum = rule.parameters?.minimum_validity_days;
  const statedPeriod =
    typeof rule.parameters?.stated_period === "string"
      ? (rule.parameters.stated_period as string)
      : null;

  if (typeof minimum !== "number") {
    return {
      id: "passport_validity",
      label: "Passport validity",
      status: "RULE_UNVERIFIED",
      detail:
        `Dataset states "${statedPeriod ?? "a minimum validity"}" but does not provide a numeric threshold or reference date. ` +
        "Manual confirmation required before relying on this check.",
    };
  }

  const expiry = parseDate(application.passportExpiryDate);
  if (!expiry) {
    return {
      id: "passport_validity",
      label: "Passport validity",
      status: "NOT_YET_CHECKED",
      detail: "Passport expiry date is not recorded, so validity cannot be evaluated.",
    };
  }

  const reference = parseDate(application.plannedTravelDate);
  if (!reference) {
    return {
      id: "passport_validity",
      label: "Passport validity",
      status: "NOT_YET_CHECKED",
      detail: "Planned travel date is not recorded; the reference date for validity is unknown.",
    };
  }

  const daysRemaining = Math.floor((expiry.getTime() - reference.getTime()) / MS_PER_DAY);
  if (daysRemaining < minimum) {
    return {
      id: "passport_validity",
      label: "Passport validity",
      status: "POTENTIAL_ISSUE",
      detail: `Passport has ${daysRemaining} days validity at the planned travel date, below the configured minimum of ${minimum} days.`,
    };
  }

  return {
    id: "passport_validity",
    label: "Passport validity",
    status: "SATISFIED",
    detail: `Passport has ${daysRemaining} days validity at the planned travel date (configured minimum ${minimum} days).`,
  };
}

function collectMissingInformationChecks(application: VisaApplication): CheckResult[] {
  const checks: CheckResult[] = [];
  if (!application.plannedTravelDate) {
    checks.push({
      id: "planned_travel_date",
      label: "Planned travel date",
      status: "NOT_YET_CHECKED",
      detail: "No planned travel date recorded; date-dependent checks cannot run.",
    });
  }
  if (!application.passportExpiryDate) {
    checks.push({
      id: "passport_expiry_date",
      label: "Passport expiry date",
      status: "NOT_YET_CHECKED",
      detail: "No passport expiry date recorded; validity checks cannot run.",
    });
  }
  return checks;
}

function determineOverallStatus(
  scope: ApplicantScopeCheck,
  categorySupported: boolean,
  results: RequirementResult[],
  checks: CheckResult[],
  hasUnverifiedCategoryItems: boolean,
): ReadinessStatus {
  if (!scope.supported || !categorySupported) return "MANUAL_REVIEW_REQUIRED";

  const all = [...results, ...checks];
  const has = (status: ReadinessStatus) => all.some((item) => item.status === status);

  if (has("MISSING")) return "MISSING";
  if (has("MANUAL_REVIEW_REQUIRED") || hasUnverifiedCategoryItems) {
    return "MANUAL_REVIEW_REQUIRED";
  }
  if (has("RULE_UNVERIFIED")) return "RULE_UNVERIFIED";
  if (has("POTENTIAL_ISSUE")) return "POTENTIAL_ISSUE";
  if (has("NOT_YET_CHECKED")) return "NOT_YET_CHECKED";
  return "SATISFIED";
}

function buildRecommendedActions(
  report: Omit<ReadinessReport, "recommendedActions">,
): string[] {
  const actions: string[] = [];

  if (!report.scope.supported) {
    actions.push("Applicant is outside the supported scope; route to manual eligibility review.");
  }
  if (!report.categorySupported) {
    actions.push("Selected visa category is not in the supported set; confirm the correct product.");
  }
  for (const missing of report.missingDocuments) {
    actions.push(`Request the missing document: ${missing.requirementName}.`);
  }
  for (const notChecked of report.notYetChecked) {
    actions.push(`Review the uploaded ${notChecked.requirementName} and record a decision.`);
  }
  for (const issue of report.potentialIssues) {
    actions.push(`Resolve the flagged issue for ${issue.requirementName}.`);
  }
  if (report.unverifiedCategoryItems.length > 0) {
    actions.push(
      `Manually confirm ${report.unverifiedCategoryItems.length} unverified requirement(s) with the authorized issuing channel.`,
    );
  }
  if (report.manualReviewItems.length > 0) {
    actions.push("Complete the listed manual-review items before submission.");
  }
  if (actions.length === 0) {
    actions.push("No outstanding actions from the automated readiness checks.");
  }
  return actions;
}

/**
 * Deterministic document-readiness evaluation for a single application.
 *
 * This never guarantees visa approval and never marks an application ready
 * while any relevant rule is unverified, any category-level item is unverified,
 * the applicant is out of scope, or the selected category is unsupported.
 */
export function evaluateApplicationReadiness(
  dataset: VisaRequirementsDataset,
  application: VisaApplication,
): ReadinessReport {
  const categoryRequirements = getRequirementsForCategory(
    dataset,
    application.visaCategoryId,
  );

  const scope = checkApplicantScope(dataset, application.profile);

  if (!categoryRequirements) {
    const report: Omit<ReadinessReport, "recommendedActions"> = {
      applicationId: application.id,
      visaCategoryId: application.visaCategoryId,
      visaCategoryName: "Unknown category",
      categorySupported: false,
      scope,
      overallStatus: "MANUAL_REVIEW_REQUIRED",
      readyForSubmission: false,
      allMandatoryDocumentsSatisfied: false,
      results: [],
      checks: [
        {
          id: "category_exists",
          label: "Visa category",
          status: "MANUAL_REVIEW_REQUIRED",
          detail: `Visa category "${application.visaCategoryId}" was not found in the dataset.`,
        },
      ],
      notApplicableConditional: [],
      missingDocuments: [],
      potentialIssues: [],
      manualReviewItems: [],
      unverifiedRequirements: [],
      notYetChecked: [],
      satisfied: [],
      unverifiedCategoryItems: [],
      generatedAt: new Date().toISOString(),
      disclaimer: dataset.verification_policy?.official_decision_disclaimer ?? "",
    };
    return { ...report, recommendedActions: buildRecommendedActions(report) };
  }

  const documentResults = categoryRequirements.documents.map((requirement) =>
    evaluateDocumentRequirement(
      requirement,
      application.documents.filter((doc) => doc.requirementId === requirement.id),
    ),
  );

  const conditional = evaluateConditionalDocuments(categoryRequirements, application);

  const checks: CheckResult[] = [
    {
      id: "applicant_scope",
      label: "Applicant scope",
      status: scope.supported ? "SATISFIED" : "MANUAL_REVIEW_REQUIRED",
      detail: scope.reason,
    },
    {
      id: "category_supported",
      label: "Supported category",
      status: categoryRequirements.supported ? "SATISFIED" : "MANUAL_REVIEW_REQUIRED",
      detail: categoryRequirements.supported
        ? "Category is within the supported scope."
        : "Category is present in the dataset but not in the supported set.",
    },
    {
      id: "category_eligibility",
      label: "Category eligibility verified",
      status: categoryRequirements.visaType.eligibility?.verified
        ? "SATISFIED"
        : "RULE_UNVERIFIED",
      detail: categoryRequirements.visaType.eligibility?.verified
        ? "Eligibility is marked verified in the dataset."
        : (categoryRequirements.visaType.eligibility?.conditions.join(" ") ??
          "Eligibility is not verified."),
    },
    evaluatePassportValidity(categoryRequirements, application),
    ...collectMissingInformationChecks(application),
  ];

  const allResults = [...documentResults, ...conditional.results];
  const hasUnverifiedCategoryItems = categoryRequirements.unverifiedItems.length > 0;

  const overallStatus = determineOverallStatus(
    scope,
    categoryRequirements.supported,
    allResults,
    checks,
    hasUnverifiedCategoryItems,
  );

  const mandatory = documentResults.filter((result) => result.mandatory);
  const allMandatoryDocumentsSatisfied =
    mandatory.length > 0 && mandatory.every((result) => result.status === "SATISFIED");

  const report: Omit<ReadinessReport, "recommendedActions"> = {
    applicationId: application.id,
    visaCategoryId: application.visaCategoryId,
    visaCategoryName: categoryRequirements.visaType.display_name,
    categorySupported: categoryRequirements.supported,
    scope,
    overallStatus,
    readyForSubmission:
      overallStatus === "SATISFIED" &&
      scope.supported &&
      categoryRequirements.supported &&
      !hasUnverifiedCategoryItems,
    allMandatoryDocumentsSatisfied,
    results: allResults,
    checks,
    notApplicableConditional: conditional.notes,
    missingDocuments: allResults.filter((result) => result.status === "MISSING"),
    potentialIssues: allResults.filter((result) => result.status === "POTENTIAL_ISSUE"),
    manualReviewItems: allResults.filter(
      (result) => result.status === "MANUAL_REVIEW_REQUIRED",
    ),
    unverifiedRequirements: allResults.filter(
      (result) => result.status === "RULE_UNVERIFIED",
    ),
    notYetChecked: allResults.filter((result) => result.status === "NOT_YET_CHECKED"),
    satisfied: allResults.filter((result) => result.status === "SATISFIED"),
    unverifiedCategoryItems: categoryRequirements.unverifiedItems,
    generatedAt: new Date().toISOString(),
    disclaimer: dataset.verification_policy?.official_decision_disclaimer ?? "",
  };

  return { ...report, recommendedActions: buildRecommendedActions(report) };
}
