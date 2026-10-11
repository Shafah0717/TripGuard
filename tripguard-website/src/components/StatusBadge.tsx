import type { ReadinessStatus } from "../utils/visaReadiness";
import { readinessStatusDisplay } from "../utils/statusDisplay";

export function StatusBadge({ status }: { status: ReadinessStatus }) {
  const display = readinessStatusDisplay(status);
  return <span className={`badge ${display.className}`}>{display.label}</span>;
}
