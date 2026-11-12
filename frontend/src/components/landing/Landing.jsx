import { useEffect, useState } from "react";
import "./landing.css";
import Icon from "../layout/Icons.jsx";
import { api } from "../../config.js";

const FEATURES = [
  ["receipt", "GST bills in seconds", "Multi-item bills with CGST, SGST, HSN and your shop details. Print them or send them on WhatsApp."],
  ["pill", "Stock with batch and expiry", "Every batch tracked separately, with alerts before anything expires. Expired stock cannot be billed."],
  ["wallet", "Udhaar without the notebook", "Customer accounts, part payments, credit limits, statements and WhatsApp reminders."],
  ["plus", "Purchases and suppliers", "Enter a supplier's bill once. Stock, cost price and what you owe all update together."],
  ["chart", "Reports your accountant wants", "Sales register, HSN summary, input GST and profit, ready to open in Excel."],
  ["shield", "Staff you can trust", "Owner, pharmacist and cashier logins, with an activity log of who did what."],
];

const FAQ = [
  ["Do I need to install anything?", "No. MedTrack runs in your browser on a computer, tablet or phone. You can also add it to your phone's home screen."],
  ["What happens if the internet goes?", "The app still opens and your bill stays on screen. Press Generate bill again once you are back online. A bill can never be saved twice."],
  ["Is my data private?", "Each shop's data is kept apart from every other shop. Only people you add can see it, and you can download all of it whenever you like."],
  ["Does it file my GST return?", "No. It gives you the figures and files your accountant needs. Please have them checked before filing."],
];

const FAQ_FREE = ["Will it always be free?", "It is free right now. If we start charging later, we will tell you at least 30 days before, with the price. Even then you can still see and download all your data without paying."];
const FAQ_PAID = ["What happens when my trial ends?", "You can still see and download everything. Billing pauses until you renew. Your data is not deleted."];

/** The public front page, shown to visitors who are not signed in. */
function Landing({ onLogin, onSignup }) {
  const [info, setInfo] = useState(null);

  useEffect(() => {
    fetch(api("/api/public"))
      .then((response) => (response.ok ? response.json() : null))
      .then(setInfo)
      .catch(() => {});
  }, []);

  const trialDays = info?.trialDays ?? 14;
  // Prices appear only once charging is switched on, never while it is free.
  const paid = info ? !info.free : false;
  const startLabel = paid ? `Start ${trialDays} day free trial` : "Start free";

  return (
    <div className="landing">
      <header className="land-nav">
        <div className="land-brand">
          <span className="land-logo"><Icon name="pill" size={20} /></span>
          MedTrack
        </div>
        <nav>
          <a href="#features">Features</a>
          {paid && <a href="#pricing">Pricing</a>}
          <a href="#faq">Questions</a>
          <button type="button" className="land-link" onClick={onLogin}>Log in</button>
          <button type="button" className="land-cta small" onClick={onSignup}>Start free</button>
        </nav>
      </header>

      <section className="land-hero">
        <h1>Run your medical store without the paperwork.</h1>
        <p>
          GST billing, stock with expiry alerts, udhaar and purchases in one simple app.
          Made for Indian pharmacies.
        </p>
        <div className="land-actions">
          <button type="button" className="land-cta" onClick={onSignup}>
            {startLabel}
          </button>
          <button type="button" className="land-ghost" onClick={onLogin}>Log in</button>
        </div>
        <small>{paid ? "No card needed." : "Free to use. No card needed."} Works on your computer and phone.</small>
      </section>

      <section className="land-section" id="features">
        <h2>Everything the counter needs</h2>
        <div className="land-features">
          {FEATURES.map(([icon, title, text]) => (
            <article key={title}>
              <span className="land-icon"><Icon name={icon} size={20} /></span>
              <h3>{title}</h3>
              <p>{text}</p>
            </article>
          ))}
        </div>
      </section>

      {paid ? (
        <section className="land-section tint" id="pricing">
          <h2>Simple pricing</h2>
          <p className="land-sub">Every feature is included. One price per shop, with unlimited bills and staff.</p>
          <div className="land-prices">
            <article>
              <h3>Monthly</h3>
              <strong>₹{(info?.priceMonthly ?? 499).toLocaleString("en-IN")}</strong>
              <span>per month</span>
            </article>
            <article className="best">
              <h3>Yearly</h3>
              <strong>₹{(info?.priceYearly ?? 4999).toLocaleString("en-IN")}</strong>
              <span>per year</span>
            </article>
          </div>
          <button type="button" className="land-cta" onClick={onSignup}>
            Try it free for {trialDays} days
          </button>
        </section>
      ) : (
        <section className="land-section tint">
          <h2>Free to use</h2>
          <p className="land-sub">Every feature is included, with unlimited bills and staff.</p>
          <button type="button" className="land-cta" onClick={onSignup}>Start free</button>
        </section>
      )}

      <section className="land-section" id="faq">
        <h2>Common questions</h2>
        <div className="land-faq">
          {[...FAQ, paid ? FAQ_PAID : FAQ_FREE].map(([question, answer]) => (
            <details key={question}>
              <summary>{question}</summary>
              <p>{answer}</p>
            </details>
          ))}
        </div>
      </section>

      <footer className="land-footer">
        <span>© {new Date().getFullYear()} MedTrack</span>
        <a href="/terms.html">Terms</a>
        <a href="/privacy.html">Privacy</a>
        {info?.supportEmail && <a href={`mailto:${info.supportEmail}`}>{info.supportEmail}</a>}
        {info?.supportPhone && <a href={`tel:${info.supportPhone}`}>{info.supportPhone}</a>}
      </footer>
    </div>
  );
}

export default Landing;
