import { useMemo } from "react";
import type { VisaRequirementsDataset } from "../types/visa";
import type { VisaApplication } from "../types/application";
import {
  evaluateApplicationReadiness,
  type ReadinessReport,
} from "../utils/visaReadiness";

/** Evaluate readiness for a list of applications, keyed by application id. */
export function useReadinessReports(
  dataset: VisaRequirementsDataset,
  applications: VisaApplication[],
): Map<string, ReadinessReport> {
  return useMemo(() => {
    const map = new Map<string, ReadinessReport>();
    for (const application of applications) {
      map.set(application.id, evaluateApplicationReadiness(dataset, application));
    }
    return map;
  }, [dataset, applications]);
}
