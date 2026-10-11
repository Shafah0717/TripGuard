import { RequirementsGate } from "../components/RequirementsGate";
import {
  getRequirementsForCategory,
  getVisaCategories,
  type VisaCategorySummary,
} from "../services/visaRequirementsService";
import type { VisaRequirementsDataset } from "../types/visa";
import { formatDate } from "../utils/statusDisplay";

function CategoryCard({
  dataset,
  category,
}: {
  dataset: VisaRequirementsDataset;
  category: VisaCategorySummary;
}) {
  const requirements = getRequirementsForCategory(dataset, category.id);
  if (!requirements) return null;

  return (
    <div className="card">
      <div className="card__header">
        <div>
          <h2 className="card__title">{category.displayName}</h2>
          <p className="small muted" style={{ margin: 0 }}>
            {category.officialName}
          </p>
        </div>
        <div className="row">
          <span
            className={`badge ${category.supported ? "badge--green" : "badge--red"}`}
          >
            {category.supported ? "Supported" : "Unsupported"}
          </span>
          <span
            className={`badge ${
              requirements.visaType.eligibility.verified
                ? "badge--green"
                : "badge--orange"
            }`}
          >
            {requirements.visaType.eligibility.verified
              ? "Eligibility verified"
              : "Eligibility unverified"}
          </span>
        </div>
      </div>

      <div className="info-grid" style={{ marginBottom: "0.75rem" }}>
        <div>
          <div className="info-item__label">Product status</div>
          <div className="info-item__value mono small">
            {requirements.visaType.product_status}
          </div>
        </div>
        <div>
          <div className="info-item__label">Entry type</div>
          <div className="info-item__value">{requirements.visaType.entry_type}</div>
        </div>
        <div>
          <div className="info-item__label">Permitted stay</div>
          <div className="info-item__value">
            {requirements.visaType.permitted_stay.value ?? "—"}{" "}
            {requirements.visaType.permitted_stay.unit}
          </div>
        </div>
        <div>
          <div className="info-item__label">Visa validity</div>
          <div className="info-item__value">
            {requirements.visaType.visa_validity.value ?? "Not specified"}{" "}
            {requirements.visaType.visa_validity.value
              ? requirements.visaType.visa_validity.unit
              : ""}
          </div>
        </div>
        <div>
          <div className="info-item__label">Last verified</div>
          <div className="info-item__value">
            {formatDate(requirements.visaType.last_verified_at)}
          </div>
        </div>
      </div>

      <div className="section-title">
        <h3>Requirements</h3>
        <span className="badge badge--slate">{requirements.documents.length}</span>
      </div>
      <ul className="list">
        {requirements.documents.map((document) => (
          <li className="list__item" key={document.id}>
            <div className="row row--between">
              <strong>{document.name}</strong>
              <span className="badge badge--slate">
                {document.mandatory ? "Mandatory" : "Optional"}
              </span>
            </div>
            {document.specifications.other_conditions?.length ? (
              <p className="small" style={{ margin: "0.35rem 0 0" }}>
                {document.specifications.other_conditions.join(" ")}
              </p>
            ) : null}
          </li>
        ))}
      </ul>

      {requirements.unverifiedItems.length > 0 ? (
        <>
          <div className="section-title">
            <h3>Unverified / needs confirmation</h3>
            <span className="badge badge--orange">
              {requirements.unverifiedItems.length}
            </span>
          </div>
          <ul className="list">
            {requirements.unverifiedItems.map((item) => (
              <li className="list__item" key={item.id}>
                <strong>{item.label}</strong>
                <p className="small" style={{ margin: "0.35rem 0 0" }}>
                  {item.detail}
                </p>
                {item.sourceIds.length > 0 ? (
                  <p className="tag-source" style={{ margin: "0.15rem 0 0" }}>
                    Sources: {item.sourceIds.join(", ")}
                  </p>
                ) : null}
              </li>
            ))}
          </ul>
        </>
      ) : null}

      {requirements.sources.length > 0 ? (
        <>
          <div className="section-title">
            <h3>Sources</h3>
          </div>
          <ul className="list">
            {requirements.sources.map((source) => (
              <li className="list__item" key={source.id}>
                <strong className="mono">{source.id}</strong> — {source.title}
                <div className="small muted">{source.organization}</div>
                <a className="small" href={source.url} target="_blank" rel="noreferrer">
                  {source.url}
                </a>
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </div>
  );
}

function RequirementsContent({ dataset }: { dataset: VisaRequirementsDataset }) {
  const categories = getVisaCategories(dataset);

  return (
    <>
      <div className="page__header">
        <div>
          <h1>Visa categories</h1>
          <p className="muted">
            Dataset {dataset.dataset_id} v{dataset.schema_version} for{" "}
            {dataset.destination_emirate}, {dataset.destination_country}. Research status:{" "}
            {dataset.research_metadata.research_status}.
          </p>
        </div>
      </div>

      <div className="notice notice--info" style={{ marginBottom: "1rem" }}>
        {dataset.verification_policy?.official_decision_disclaimer}
      </div>

      {categories.map((category) => (
        <CategoryCard key={category.id} dataset={dataset} category={category} />
      ))}

      <div className="card">
        <h2 className="card__title">Dataset limitations</h2>
        <ul className="list">
          {dataset.limitations.map((limitation) => (
            <li className="list__item list__item--muted" key={limitation}>
              {limitation}
            </li>
          ))}
        </ul>
      </div>

      <div className="card">
        <h2 className="card__title">Verification policy</h2>
        <p className="small muted">Mode: {dataset.verification_policy?.mode}</p>
        <div className="info-grid">
          <div>
            <div className="info-item__label">Automatic checks allowed</div>
            <ul className="small" style={{ margin: "0.35rem 0 0", paddingLeft: "1.1rem" }}>
              {dataset.verification_policy?.automatic_checks_allowed.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>
          <div>
            <div className="info-item__label">Never automatically decided</div>
            <ul className="small" style={{ margin: "0.35rem 0 0", paddingLeft: "1.1rem" }}>
              {dataset.verification_policy?.never_automatically_decide.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>
        </div>
      </div>
    </>
  );
}

export function RequirementsPage() {
  return (
    <RequirementsGate>{(dataset) => <RequirementsContent dataset={dataset} />}</RequirementsGate>
  );
}
