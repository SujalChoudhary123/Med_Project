import { useEffect, useState } from "react";
import "../business.css";
import { api } from "../../config.js";
import { inr } from "../../utils/invoiceUtils.js";
import { friendlyError, readJson } from "../../utils/apiHelpers.js";

const STATE_LABEL = {
  free: "Free",
  trial: "Free trial",
  active: "Active",
  grace: "Ended, grace period",
  expired: "Ended",
  disabled: "Suspended",
};

const niceDate = (value) => {
  const date = new Date(`${value}T00:00:00`);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleDateString("en-IN", { day: "2-digit", month: "long", year: "numeric" });
};

function PlanPage() {
  const [plan, setPlan] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    fetch(api("/api/plan"))
      .then(readJson)
      .then(setPlan)
      .catch((problem) => setError(friendlyError(problem)));
  }, []);

  if (error) {
    return <div className="bill-page"><div className="bill-message error">{error}</div></div>;
  }

  if (!plan) {
    return <div className="bill-page"><div className="bill-empty small">Loading...</div></div>;
  }

  const { pricing } = plan;
  const contactLink = pricing.supportPhone
    ? `https://wa.me/${pricing.supportPhone.replace(/\D/g, "")}?text=${encodeURIComponent("Hello, I want to renew MedTrack.")}`
    : "";

  return (
    <div className="bill-page">
      <section className="dashboard-card plan-card">
        <div>
          <span className={`plan-pill ${plan.state}`}>{STATE_LABEL[plan.state] || plan.state}</span>
          <h2>
            {plan.state === "free"
              ? "MedTrack is free for now"
              : plan.state === "expired"
              ? "Your plan has ended"
              : plan.daysLeft >= 0
                ? `${plan.daysLeft} day${plan.daysLeft === 1 ? "" : "s"} left`
                : "Please renew now"}
          </h2>
          <p>
            {plan.endsOn
              ? `${plan.state === "expired" ? "Ended on" : plan.state === "free" ? "Paid plans start on" : "Valid until"} ${niceDate(plan.endsOn)}. `
              : ""}
            {plan.canWrite
              ? "Everything is working normally."
              : "You can still see and download all your data. Renew to start billing again."}
          </p>
        </div>
      </section>

      <section className="plan-prices">
        <div className="dashboard-card">
          <h3>Monthly</h3>
          <strong>{inr(pricing.priceMonthly).replace(".00", "")}</strong>
          <span>per shop, per month</span>
        </div>
        <div className="dashboard-card">
          <h3>Yearly</h3>
          <strong>{inr(pricing.priceYearly).replace(".00", "")}</strong>
          <span>per shop, per year</span>
        </div>
      </section>

      <section className="dashboard-card">
        <div className="card-header">
          <div>
            <h2>How to renew</h2>
            <p>Your days are added as soon as the payment is confirmed.</p>
          </div>
        </div>
        <ol className="plan-steps">
          {plan.payUpi ? (
            <li>Pay by UPI to <strong>{plan.payUpi}</strong>.</li>
          ) : (
            <li>Contact us for payment details.</li>
          )}
          <li>Send the payment screenshot with your shop name.</li>
          {plan.payNote && <li>{plan.payNote}</li>}
        </ol>
        <div className="plan-contact">
          {contactLink && (
            <a className="primary-button" href={contactLink} target="_blank" rel="noopener noreferrer">
              WhatsApp {pricing.supportPhone}
            </a>
          )}
          {pricing.supportEmail && (
            <a className="secondary-button" href={`mailto:${pricing.supportEmail}`}>
              {pricing.supportEmail}
            </a>
          )}
        </div>
      </section>
    </div>
  );
}

export default PlanPage;
