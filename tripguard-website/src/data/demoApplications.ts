import type { VisaApplication } from "../types/application";

/**
 * Synthetic demo records. These are clearly labelled (`isDemo: true`) and must
 * never be presented as real agency activity. There is no backend in this
 * project, so all reviewer changes live in front-end state only.
 */
export const DEMO_APPLICATIONS: VisaApplication[] = [
  {
    id: "TG-2026-0001",
    applicantIdentifier: "P-****4821 (R. Sharma)",
    profile: { nationality: "Indian", passport_type: "ordinary", purpose: "tourism" },
    visaCategoryId: "tourist_30_day_single_entry",
    plannedTravelDate: "2026-11-15",
    passportExpiryDate: "2031-04-20",
    submittedAt: "2026-10-08T09:12:00Z",
    stage: "AWAITING_REVIEW",
    isDemo: true,
    documents: [
      doc("TG-2026-0001-d1", "personal_photo", "rsharma_photo.jpg", "2026-10-08T09:10:00Z"),
      doc("TG-2026-0001-d2", "passport_copy", "rsharma_passport.pdf", "2026-10-08T09:11:00Z"),
      doc("TG-2026-0001-d3", "travel_ticket", "rsharma_ticket.pdf", "2026-10-08T09:11:30Z"),
      doc("TG-2026-0001-d4", "medical_insurance", "rsharma_insurance.pdf", "2026-10-08T09:12:00Z"),
    ],
    notes: [],
  },
  {
    id: "TG-2026-0002",
    applicantIdentifier: "P-****7734 (A. Iyer)",
    profile: { nationality: "Indian", passport_type: "ordinary", purpose: "tourism" },
    visaCategoryId: "tourist_60_day_single_entry",
    plannedTravelDate: "2026-12-02",
    passportExpiryDate: "2029-01-10",
    submittedAt: "2026-10-09T06:45:00Z",
    stage: "IN_REVIEW",
    isDemo: true,
    documents: [
      doc("TG-2026-0002-d1", "personal_photo", "aiyer_photo.jpg", "2026-10-09T06:40:00Z", "REVIEWED_OK"),
      doc("TG-2026-0002-d2", "passport_copy", "aiyer_passport.pdf", "2026-10-09T06:41:00Z", "REVIEWED_OK"),
      doc("TG-2026-0002-d3", "travel_ticket", "aiyer_ticket.pdf", "2026-10-09T06:42:00Z", "REVIEWED_OK"),
    ],
    notes: [],
  },
  {
    id: "TG-2026-0003",
    applicantIdentifier: "P-****1109 (S. Nair)",
    profile: { nationality: "Indian", passport_type: "ordinary", purpose: "tourism" },
    visaCategoryId: "tourist_5_year_multiple_entry",
    plannedTravelDate: "2026-11-28",
    passportExpiryDate: "2033-07-30",
    submittedAt: "2026-10-07T14:20:00Z",
    stage: "IN_REVIEW",
    isDemo: true,
    documents: [
      doc("TG-2026-0003-d1", "personal_photo", "snair_photo.jpg", "2026-10-07T14:10:00Z", "REVIEWED_OK"),
      doc("TG-2026-0003-d2", "passport_copy", "snair_passport.pdf", "2026-10-07T14:12:00Z", "REVIEWED_OK"),
      doc("TG-2026-0003-d3", "bank_statement", "snair_bank_6m.pdf", "2026-10-07T14:15:00Z", "REVIEWED_OK"),
      doc("TG-2026-0003-d4", "health_insurance", "snair_insurance.pdf", "2026-10-07T14:18:00Z", "REVIEWED_OK"),
      doc("TG-2026-0003-d5", "travel_ticket", "snair_ticket.pdf", "2026-10-07T14:20:00Z", "REVIEWED_OK"),
    ],
    notes: [
      {
        id: "note-tg3-1",
        author: "Demo Reviewer",
        createdAt: "2026-10-07T15:00:00Z",
        body: "Bank balance wording differs between sources; send to senior reviewer for the USD 4,000 interpretation.",
        kind: "note",
      },
    ],
  },
  {
    id: "TG-2026-0004",
    applicantIdentifier: "P-****5567 (L. Fernandes)",
    profile: { nationality: "Portuguese", passport_type: "ordinary", purpose: "tourism" },
    visaCategoryId: "tourist_30_day_single_entry",
    plannedTravelDate: "2026-11-20",
    passportExpiryDate: "2030-05-15",
    submittedAt: "2026-10-09T10:05:00Z",
    stage: "AWAITING_REVIEW",
    isDemo: true,
    documents: [
      doc("TG-2026-0004-d1", "personal_photo", "lfernandes_photo.jpg", "2026-10-09T10:00:00Z"),
      doc("TG-2026-0004-d2", "passport_copy", "lfernandes_passport.pdf", "2026-10-09T10:03:00Z"),
    ],
    notes: [],
  },
  {
    id: "TG-2026-0005",
    applicantIdentifier: "P-****3302 (D. Gupta)",
    profile: { nationality: "Indian", passport_type: "ordinary", purpose: "tourism" },
    visaCategoryId: "tourist_30_day_single_entry",
    plannedTravelDate: null,
    passportExpiryDate: "2028-09-09",
    submittedAt: "2026-10-10T07:30:00Z",
    stage: "AWAITING_REVIEW",
    isDemo: true,
    documents: [
      doc("TG-2026-0005-d1", "personal_photo", "dgupta_photo.png", "2026-10-10T07:25:00Z"),
      doc("TG-2026-0005-d2", "passport_copy", "dgupta_passport.pdf", "2026-10-10T07:27:00Z"),
      doc("TG-2026-0005-d3", "travel_ticket", "dgupta_ticket.pdf", "2026-10-10T07:28:00Z"),
      doc("TG-2026-0005-d4", "medical_insurance", "dgupta_insurance.pdf", "2026-10-10T07:30:00Z"),
    ],
    notes: [],
  },
  {
    id: "TG-2026-0006",
    applicantIdentifier: "P-****9088 (M. Khan)",
    profile: { nationality: "Indian", passport_type: "ordinary", purpose: "tourism" },
    visaCategoryId: "tourist_5_year_multiple_entry",
    plannedTravelDate: "2026-12-15",
    passportExpiryDate: "2027-02-01",
    submittedAt: "2026-10-06T11:00:00Z",
    stage: "IN_REVIEW",
    isDemo: true,
    documents: [
      doc("TG-2026-0006-d1", "personal_photo", "mkhan_photo.jpg", "2026-10-06T10:50:00Z", "REVIEWED_OK"),
      doc("TG-2026-0006-d2", "passport_copy", "mkhan_passport.pdf", "2026-10-06T10:52:00Z", "NEEDS_REUPLOAD"),
      doc("TG-2026-0006-d3", "bank_statement", "mkhan_bank.pdf", "2026-10-06T10:55:00Z", "REVIEWED_OK"),
      doc("TG-2026-0006-d4", "health_insurance", "mkhan_insurance.pdf", "2026-10-06T10:57:00Z"),
      doc("TG-2026-0006-d5", "travel_ticket", "mkhan_ticket.pdf", "2026-10-06T11:00:00Z"),
    ],
    notes: [],
  },
  {
    id: "TG-2026-0007",
    applicantIdentifier: "P-****2244 (K. Rao)",
    profile: { nationality: "Indian", passport_type: "ordinary", purpose: "tourism" },
    visaCategoryId: "tourist_90_day_single_entry",
    plannedTravelDate: "2027-01-05",
    passportExpiryDate: "2031-01-01",
    submittedAt: "2026-10-05T16:00:00Z",
    stage: "AWAITING_REVIEW",
    isDemo: true,
    documents: [
      doc("TG-2026-0007-d1", "personal_photo", "krao_photo.jpg", "2026-10-05T15:55:00Z"),
    ],
    notes: [],
  },
];

function doc(
  id: string,
  requirementId: string,
  fileName: string,
  uploadedAt: string,
  reviewStatus: "UPLOADED" | "REVIEWED_OK" | "NEEDS_REUPLOAD" = "UPLOADED",
) {
  return { id, requirementId, fileName, uploadedAt, reviewStatus };
}
