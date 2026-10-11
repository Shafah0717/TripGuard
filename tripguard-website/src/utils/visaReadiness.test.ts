import { describe, expect, it } from "vitest";
import rawDataset from "../../public/data/visa-requirements.json";
import type { VisaRequirementsDataset } from "../types/visa";
import type { UploadedDocument, VisaApplication } from "../types/application";
import { evaluateApplicationReadiness } from "./visaReadiness";

const dataset = rawDataset as unknown as VisaRequirementsDataset;

function doc(
  requirementId: string,
  reviewStatus: UploadedDocument["reviewStatus"] = "UPLOADED",
): UploadedDocument {
  return {
    id: `${requirementId}-${reviewStatus}`,
    requirementId,
    fileName: `${requirementId}.pdf`,
    uploadedAt: "2026-10-01T00:00:00Z",
    reviewStatus,
  };
}

function app(overrides: Partial<VisaApplication> = {}): VisaApplication {
  return {
    id: "TG-TEST-0001",
    applicantIdentifier: "P-****0000 (Test)",
    profile: { nationality: "Indian", passport_type: "ordinary", purpose: "tourism" },
    visaCategoryId: "tourist_30_day_single_entry",
    plannedTravelDate: "2026-11-15",
    passportExpiryDate: "2031-04-20",
    submittedAt: "2026-10-01T00:00:00Z",
    stage: "AWAITING_REVIEW",
    documents: [],
    notes: [],
    isDemo: true,
    ...overrides,
  };
}

describe("evaluateApplicationReadiness", () => {
  it("identifies missing documents", () => {
    const application = app({
      visaCategoryId: "tourist_60_day_single_entry",
      documents: [
        doc("personal_photo", "REVIEWED_OK"),
        doc("passport_copy", "REVIEWED_OK"),
        doc("travel_ticket", "REVIEWED_OK"),
      ],
    });
    const report = evaluateApplicationReadiness(dataset, application);
    expect(report.missingDocuments.map((item) => item.requirementId)).toContain(
      "medical_insurance",
    );
    expect(report.overallStatus).toBe("MISSING");
    expect(report.readyForSubmission).toBe(false);
  });

  it("does not treat an uploaded-but-unchecked document as satisfied", () => {
    const application = app({
      documents: [
        doc("personal_photo"),
        doc("passport_copy"),
        doc("travel_ticket"),
        doc("medical_insurance"),
      ],
    });
    const report = evaluateApplicationReadiness(dataset, application);
    const passport = report.results.find((item) => item.requirementId === "passport_copy");
    expect(passport?.status).toBe("NOT_YET_CHECKED");
    expect(report.satisfied).toHaveLength(0);
    expect(report.overallStatus).not.toBe("SATISFIED");
  });

  it("keeps unverified rules visible and never marks the application ready", () => {
    const application = app({
      documents: [
        doc("personal_photo", "REVIEWED_OK"),
        doc("passport_copy", "REVIEWED_OK"),
        doc("travel_ticket", "REVIEWED_OK"),
        doc("medical_insurance", "REVIEWED_OK"),
      ],
    });
    const report = evaluateApplicationReadiness(dataset, application);
    expect(report.unverifiedCategoryItems.length).toBeGreaterThan(0);
    expect(
      report.results.find((item) => item.requirementId === "passport_copy")?.status,
    ).toBe("RULE_UNVERIFIED");
    expect(report.readyForSubmission).toBe(false);
    expect(report.overallStatus).toBe("MANUAL_REVIEW_REQUIRED");
  });

  it("cannot mark an out-of-scope applicant ready", () => {
    const application = app({
      profile: { nationality: "Portuguese", passport_type: "ordinary", purpose: "tourism" },
      documents: [
        doc("personal_photo", "REVIEWED_OK"),
        doc("passport_copy", "REVIEWED_OK"),
        doc("travel_ticket", "REVIEWED_OK"),
        doc("medical_insurance", "REVIEWED_OK"),
      ],
    });
    const report = evaluateApplicationReadiness(dataset, application);
    expect(report.scope.supported).toBe(false);
    expect(report.readyForSubmission).toBe(false);
    expect(report.overallStatus).toBe("MANUAL_REVIEW_REQUIRED");
  });

  it("routes unknown categories to manual review", () => {
    const report = evaluateApplicationReadiness(
      dataset,
      app({ visaCategoryId: "tourist_14_day_unknown" }),
    );
    expect(report.categorySupported).toBe(false);
    expect(report.overallStatus).toBe("MANUAL_REVIEW_REQUIRED");
    expect(report.readyForSubmission).toBe(false);
  });

  it("marks nationality-specific conditional documents as not applicable", () => {
    const report = evaluateApplicationReadiness(dataset, app());
    const conditional = report.notApplicableConditional.find((item) =>
      item.requirementId.includes("national_identity"),
    );
    expect(conditional?.applicable).toBe(false);
    expect(report.missingDocuments.map((item) => item.requirementId)).not.toContain(
      "national_identity_document_for_specific_nationalities",
    );
  });

  it("flags the five-year financial evidence and passport rule as unverified", () => {
    const application = app({
      visaCategoryId: "tourist_5_year_multiple_entry",
      documents: [
        doc("personal_photo", "REVIEWED_OK"),
        doc("passport_copy", "REVIEWED_OK"),
        doc("bank_statement", "REVIEWED_OK"),
        doc("health_insurance", "REVIEWED_OK"),
        doc("travel_ticket", "REVIEWED_OK"),
      ],
    });
    const report = evaluateApplicationReadiness(dataset, application);
    expect(report.unverifiedCategoryItems.some((item) => item.id.includes("financial"))).toBe(
      true,
    );
    const passportCheck = report.checks.find((check) => check.id === "passport_validity");
    expect(passportCheck?.status).toBe("RULE_UNVERIFIED");
    expect(report.readyForSubmission).toBe(false);
  });

  it("produces an explanation for every requirement result", () => {
    const application = app({
      documents: [doc("personal_photo", "REVIEWED_OK")],
    });
    const report = evaluateApplicationReadiness(dataset, application);
    expect(report.results.length).toBeGreaterThan(0);
    for (const result of report.results) {
      expect(result.reason.trim().length).toBeGreaterThan(0);
    }
  });
});
