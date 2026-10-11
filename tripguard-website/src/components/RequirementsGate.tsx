import type { ReactNode } from "react";
import type { VisaRequirementsDataset } from "../types/visa";
import { useVisaRequirements } from "../state/RequirementsStore";

const ERROR_TITLES: Record<string, string> = {
  NOT_FOUND: "Visa requirements file not found",
  MALFORMED_JSON: "Visa requirements file is malformed",
  SCHEMA_MISMATCH: "Visa requirements file has an unexpected schema",
  NETWORK: "Could not load visa requirements",
  UNKNOWN: "Could not load visa requirements",
};

/**
 * Renders loading and error states for the requirements dataset, then provides
 * the validated dataset to its children. Keeps dataset-loading concerns out of
 * individual pages.
 */
export function RequirementsGate({
  children,
}: {
  children: (dataset: VisaRequirementsDataset) => ReactNode;
}) {
  const { dataset, loading, error, reload } = useVisaRequirements();

  if (loading) {
    return (
      <div className="card">
        <p>Loading visa requirements…</p>
      </div>
    );
  }

  if (error || !dataset) {
    return (
      <div className="card">
        <h2>{ERROR_TITLES[error?.code ?? "UNKNOWN"]}</h2>
        <div className="notice notice--error">
          <p style={{ color: "inherit", margin: 0 }}>
            {error?.message ?? "The visa requirements dataset is unavailable."}
          </p>
          {error?.detail ? (
            <p className="small" style={{ color: "inherit", marginTop: "0.5rem" }}>
              {error.detail}
            </p>
          ) : null}
        </div>
        <p className="small muted" style={{ marginTop: "0.75rem" }}>
          Expected file: <code>public/data/visa-requirements.json</code>. Confirm the file
          exists and is valid JSON that matches the expected schema.
        </p>
        <button type="button" className="btn" onClick={reload}>
          Retry
        </button>
      </div>
    );
  }

  return <>{children(dataset)}</>;
}
