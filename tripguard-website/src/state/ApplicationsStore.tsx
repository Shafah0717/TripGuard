import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useReducer,
  type ReactNode,
} from "react";
import type {
  ApplicationStage,
  DocumentReviewStatus,
  VisaApplication,
} from "../types/application";
import { DEMO_APPLICATIONS } from "../data/demoApplications";

/**
 * Front-end-only demo store. There is no backend, so every reviewer action is
 * held in memory for the current session and is explicitly demo-only.
 */

type Action =
  | {
      type: "REVIEW_DOCUMENT";
      applicationId: string;
      documentId: string;
      status: DocumentReviewStatus;
      comment?: string;
    }
  | {
      type: "ADD_NOTE";
      applicationId: string;
      body: string;
      kind: "note" | "info_request";
      author?: string;
    }
  | { type: "SET_STAGE"; applicationId: string; stage: ApplicationStage }
  | { type: "RESET" };

interface ApplicationsState {
  applications: VisaApplication[];
}

function createId(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`;
}

function reducer(state: ApplicationsState, action: Action): ApplicationsState {
  switch (action.type) {
    case "REVIEW_DOCUMENT":
      return {
        applications: state.applications.map((application) => {
          if (application.id !== action.applicationId) return application;
          return {
            ...application,
            documents: application.documents.map((document) =>
              document.id === action.documentId
                ? {
                    ...document,
                    reviewStatus: action.status,
                    reviewerComment: action.comment ?? document.reviewerComment,
                  }
                : document,
            ),
          };
        }),
      };
    case "ADD_NOTE":
      return {
        applications: state.applications.map((application) => {
          if (application.id !== action.applicationId) return application;
          return {
            ...application,
            notes: [
              {
                id: createId(action.kind === "info_request" ? "req" : "note"),
                author: action.author ?? "Demo Reviewer",
                createdAt: new Date().toISOString(),
                body: action.body,
                kind: action.kind,
              },
              ...application.notes,
            ],
          };
        }),
      };
    case "SET_STAGE":
      return {
        applications: state.applications.map((application) =>
          application.id === action.applicationId
            ? { ...application, stage: action.stage }
            : application,
        ),
      };
    case "RESET":
      return { applications: DEMO_APPLICATIONS };
    default:
      return state;
  }
}

interface ApplicationsContextValue extends ApplicationsState {
  getApplication: (id: string) => VisaApplication | undefined;
  reviewDocument: (
    applicationId: string,
    documentId: string,
    status: DocumentReviewStatus,
    comment?: string,
  ) => void;
  addNote: (
    applicationId: string,
    body: string,
    kind?: "note" | "info_request",
    author?: string,
  ) => void;
  setStage: (applicationId: string, stage: ApplicationStage) => void;
  reset: () => void;
}

const ApplicationsContext = createContext<ApplicationsContextValue | null>(null);

export function ApplicationsProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, { applications: DEMO_APPLICATIONS });

  const getApplication = useCallback(
    (id: string) => state.applications.find((application) => application.id === id),
    [state.applications],
  );

  const reviewDocument = useCallback(
    (
      applicationId: string,
      documentId: string,
      status: DocumentReviewStatus,
      comment?: string,
    ) => {
      dispatch({ type: "REVIEW_DOCUMENT", applicationId, documentId, status, comment });
    },
    [],
  );

  const addNote = useCallback(
    (
      applicationId: string,
      body: string,
      kind: "note" | "info_request" = "note",
      author?: string,
    ) => {
      dispatch({ type: "ADD_NOTE", applicationId, body, kind, author });
    },
    [],
  );

  const setStage = useCallback((applicationId: string, stage: ApplicationStage) => {
    dispatch({ type: "SET_STAGE", applicationId, stage });
  }, []);

  const reset = useCallback(() => dispatch({ type: "RESET" }), []);

  const value = useMemo<ApplicationsContextValue>(
    () => ({
      ...state,
      getApplication,
      reviewDocument,
      addNote,
      setStage,
      reset,
    }),
    [state, getApplication, reviewDocument, addNote, setStage, reset],
  );

  return (
    <ApplicationsContext.Provider value={value}>
      {children}
    </ApplicationsContext.Provider>
  );
}

export function useApplications(): ApplicationsContextValue {
  const context = useContext(ApplicationsContext);
  if (!context) {
    throw new Error("useApplications must be used within an ApplicationsProvider.");
  }
  return context;
}
