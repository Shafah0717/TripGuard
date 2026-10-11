import { describe, expect, it } from "vitest";
import rawDataset from "../../public/data/visa-requirements.json";
import {
  checkApplicantScope,
  getRequirementsForCategory,
  getVisaCategories,
  isSupportedCategory,
  loadVisaRequirements,
  validateVisaRequirements,
  VisaRequirementsError,
} from "./visaRequirementsService";

const validJson = JSON.stringify(rawDataset);

function jsonResponse(body: string, status = 200): Response {
  return new Response(body, {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const noopFetcher = (async () => jsonResponse(validJson)) as unknown as typeof fetch;

describe("validateVisaRequirements", () => {
  it("accepts the supplied dataset", () => {
    const result = validateVisaRequirements(rawDataset);
    expect(result.valid).toBe(true);
    expect(result.errors).toEqual([]);
  });

  it("reports a schema mismatch for an empty object", () => {
    const result = validateVisaRequirements({});
    expect(result.valid).toBe(false);
    expect(result.errors.length).toBeGreaterThan(0);
  });
});

describe("loadVisaRequirements", () => {
  it("loads and validates the dataset from the configured URL", async () => {
    const dataset = await loadVisaRequirements({ fetcher: noopFetcher });
    expect(dataset.dataset_id).toBe("tripguard_uae_visa_requirements");
    expect(dataset.visa_types).toHaveLength(3);
  });

  it("throws MALFORMED_JSON for invalid JSON", async () => {
    const fetcher = (async () => jsonResponse("{ not valid json")) as unknown as typeof fetch;
    await expect(loadVisaRequirements({ fetcher })).rejects.toMatchObject({
      code: "MALFORMED_JSON",
    });
  });

  it("throws NOT_FOUND for a 404 response", async () => {
    const fetcher = (async () => jsonResponse("", 404)) as unknown as typeof fetch;
    await expect(loadVisaRequirements({ fetcher })).rejects.toBeInstanceOf(
      VisaRequirementsError,
    );
    await expect(loadVisaRequirements({ fetcher })).rejects.toMatchObject({
      code: "NOT_FOUND",
    });
  });

  it("throws SCHEMA_MISMATCH for structurally invalid data", async () => {
    const fetcher = (async () => jsonResponse("{}")) as unknown as typeof fetch;
    await expect(loadVisaRequirements({ fetcher })).rejects.toMatchObject({
      code: "SCHEMA_MISMATCH",
    });
  });

  it("throws NETWORK when the fetcher rejects", async () => {
    const fetcher = (async () => {
      throw new Error("offline");
    }) as unknown as typeof fetch;
    await expect(loadVisaRequirements({ fetcher })).rejects.toMatchObject({
      code: "NETWORK",
    });
  });
});

describe("category mapping", () => {
  const dataset = rawDataset as unknown as Parameters<typeof getVisaCategories>[0];

  it("identifies the three supported categories", () => {
    const categories = getVisaCategories(dataset);
    expect(categories.map((category) => category.id)).toEqual([
      "tourist_30_day_single_entry",
      "tourist_60_day_single_entry",
      "tourist_5_year_multiple_entry",
    ]);
    expect(categories.every((category) => category.supported)).toBe(true);
  });

  it("maps each category to its own document set", () => {
    const thirty = getRequirementsForCategory(dataset, "tourist_30_day_single_entry");
    const sixty = getRequirementsForCategory(dataset, "tourist_60_day_single_entry");
    const fiveYear = getRequirementsForCategory(
      dataset,
      "tourist_5_year_multiple_entry",
    );

    expect(thirty?.documents.map((doc) => doc.id)).toEqual([
      "personal_photo",
      "passport_copy",
      "travel_ticket",
      "medical_insurance",
    ]);
    expect(sixty?.documents).toHaveLength(4);
    expect(fiveYear?.documents.map((doc) => doc.id)).toEqual([
      "personal_photo",
      "passport_copy",
      "bank_statement",
      "health_insurance",
      "travel_ticket",
    ]);
    expect(fiveYear?.financialRequirements).toHaveLength(2);
  });

  it("preserves unverified items for every category", () => {
    for (const category of getVisaCategories(dataset)) {
      const requirements = getRequirementsForCategory(dataset, category.id);
      expect(requirements?.unverifiedItems.length).toBeGreaterThan(0);
    }
  });

  it("flags unsupported categories", () => {
    expect(isSupportedCategory("tourist_30_day_single_entry")).toBe(true);
    expect(isSupportedCategory("some_other_visa")).toBe(false);
  });
});

describe("checkApplicantScope", () => {
  const dataset = rawDataset as unknown as Parameters<typeof checkApplicantScope>[0];

  it("accepts Indian ordinary-passport tourists", () => {
    const result = checkApplicantScope(dataset, {
      nationality: "Indian",
      passport_type: "ordinary",
      purpose: "tourism",
    });
    expect(result.supported).toBe(true);
  });

  it("rejects applicants outside the supported scope", () => {
    const result = checkApplicantScope(dataset, {
      nationality: "Portuguese",
      passport_type: "ordinary",
      purpose: "tourism",
    });
    expect(result.supported).toBe(false);
  });

  it("rejects diplomatic passports", () => {
    const result = checkApplicantScope(dataset, {
      nationality: "Indian",
      passport_type: "diplomatic",
      purpose: "tourism",
    });
    expect(result.supported).toBe(false);
  });
});
