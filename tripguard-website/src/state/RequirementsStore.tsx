import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import type { VisaRequirementsDataset } from "../types/visa";
import {
  loadVisaRequirements,
  VisaRequirementsError,
  type DatasetErrorCode,
} from "../services/visaRequirementsService";

interface RequirementsState {
  dataset: VisaRequirementsDataset | null;
  loading: boolean;
  error: { code: DatasetErrorCode | "UNKNOWN"; message: string; detail?: string } | null;
  reload: () => void;
}

const RequirementsContext = createContext<RequirementsState | null>(null);

export function RequirementsProvider({ children }: { children: ReactNode }) {
  const [dataset, setDataset] = useState<VisaRequirementsDataset | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<RequirementsState["error"]>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    loadVisaRequirements()
      .then((loaded) => {
        if (cancelled) return;
        setDataset(loaded);
        setLoading(false);
      })
      .catch((caught: unknown) => {
        if (cancelled) return;
        if (caught instanceof VisaRequirementsError) {
          setError({ code: caught.code, message: caught.message, detail: caught.detail });
        } else {
          setError({
            code: "UNKNOWN",
            message:
              caught instanceof Error ? caught.message : "Unknown error loading requirements.",
          });
        }
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [reloadToken]);

  const reload = useCallback(() => setReloadToken((token) => token + 1), []);

  const value = useMemo<RequirementsState>(
    () => ({ dataset, loading, error, reload }),
    [dataset, loading, error, reload],
  );

  return (
    <RequirementsContext.Provider value={value}>
      {children}
    </RequirementsContext.Provider>
  );
}

export function useVisaRequirements(): RequirementsState {
  const context = useContext(RequirementsContext);
  if (!context) {
    throw new Error("useVisaRequirements must be used within a RequirementsProvider.");
  }
  return context;
}
