/**
 * Types mirroring the TripGuard UAE visa-requirements dataset schema (v1.0.1).
 *
 * These types describe the *structure* supplied by the dataset. Optional fields
 * are marked optional because the dataset uses `null` and omission interchangeably
 * across categories. Fields are intentionally kept close to the source naming so
 * that no information is silently dropped between the JSON and the UI.
 */

export interface ApplicantProfile {
  nationality: string;
  passport_type: string;
  purpose: string;
  scope_note?: string;
}

export interface ResearchMetadata {
  generated_at: string;
  last_verified_at: string;
  research_status: string;
  version_notes: string[];
}

export interface PeriodValue {
  value: number | null;
  unit: string;
  conditions: string[];
}

export interface Eligibility {
  nationality: string;
  conditions: string[];
  verified: boolean;
  source_ids: string[];
}

export interface ApplicationRoute {
  sponsor_required: boolean;
  authorized_channels: string[];
  steps: string[];
  source_ids: string[];
  route_notes?: string[];
}

export interface Applicability {
  scope: string;
  conditions: string[];
}

export interface DocumentSpecifications {
  minimum_validity_days?: number | null;
  accepted_formats?: string[];
  image_requirements?: string[];
  translation_requirements?: string[];
  attestation_requirements?: string[];
  other_conditions?: string[];
}

export type RuleSeverity = "review" | "block" | "info" | string;

export interface VerificationRule {
  id: string;
  rule_type: string;
  description: string;
  parameters: Record<string, unknown>;
  severity: RuleSeverity;
  source_ids: string[];
  human_review_required: boolean;
}

export interface RouteSpecificRequirement {
  route: string;
  condition: string;
}

export interface DocumentRequirement {
  id: string;
  name: string;
  description: string | null;
  requirement_status: string;
  applicability: Applicability;
  mandatory: boolean;
  specifications: DocumentSpecifications;
  verification_rules: VerificationRule[];
  source_ids: string[];
  evidence_notes: string[];
  human_review_required: boolean;
  route_specific_requirements?: RouteSpecificRequirement[];
}

export interface ConditionalDocument {
  id: string;
  name: string;
  requirement_status: string;
  applicability: Applicability;
  mandatory?: boolean;
  mandatory_for_current_scope?: boolean;
  mandatory_for_all_routes?: boolean;
  specifications?: DocumentSpecifications;
  source_ids: string[];
  human_review_required: boolean;
}

export interface FinancialRequirement {
  id: string;
  type: string;
  amount: number | null;
  currency: string | null;
  period: string | null;
  conditions: string[];
  requirement_status: string;
  source_ids: string[];
  evidence_notes: string[];
  machine_check_allowed?: boolean;
}

export interface Fee {
  amount: number | null;
  currency: string;
  description: string;
  source_ids: string[];
}

export interface ProcessingTime {
  value: number;
  unit: string;
  conditions: string[];
  source_ids: string[];
}

export interface ExtensionRule {
  status: string;
  description: string;
  source_ids: string[];
}

export interface VisaType {
  id: string;
  official_name: string;
  display_name: string;
  category: string;
  product_status: string;
  entry_type: string;
  permitted_stay: PeriodValue;
  visa_validity: PeriodValue;
  eligibility: Eligibility;
  application_route: ApplicationRoute;
  financial_requirements: FinancialRequirement[];
  documents: DocumentRequirement[];
  fees: Fee[];
  processing_time: ProcessingTime;
  extension_rules: ExtensionRule[];
  restrictions: string[];
  sources: string[];
  last_verified_at: string;
  human_review_required: boolean;
  applicant_scope: ApplicantProfile;
  conditional_documents: ConditionalDocument[];
}

export interface SourceRecord {
  id: string;
  title: string;
  organization: string;
  url: string;
  source_type: string;
  accessed_at: string;
  published_at: string | null;
  relevant_visa_types: string[];
  supports: string[];
  evidence_excerpt: string;
  reliability_notes: string[];
}

export interface SupportedApplicantScope {
  nationality: string;
  passport_type: string;
  purpose: string;
  destination_country: string;
  destination_emirate: string;
  unsupported_applicants: string[];
  scope_enforcement: string;
}

export interface VerificationPolicy {
  mode: string;
  automatic_checks_allowed: string[];
  never_automatically_decide: string[];
  low_confidence_action: string;
  official_decision_disclaimer: string;
}

export interface VisaRequirementsDataset {
  dataset_id: string;
  schema_version: string;
  destination_country: string;
  destination_emirate: string;
  initial_applicant_profile: ApplicantProfile;
  research_metadata: ResearchMetadata;
  visa_types: VisaType[];
  sources: SourceRecord[];
  limitations: string[];
  supported_applicant_scope: SupportedApplicantScope;
  verification_policy: VerificationPolicy;
}

/**
 * The dataset is analytically useful only for the scope it was built for.
 * Anything outside this scope must be routed to manual review.
 */
export interface ApplicantScopeCheck {
  supported: boolean;
  reason: string;
}
