import { useEffect, useState } from "react";
import "../business.css";
import Icon from "../layout/Icons.jsx";
import { api } from "../../config.js";

const KEY = "medtrackSetupHidden";

function readHidden() {
  try {
    return localStorage.getItem(KEY) === "1";
  } catch {
    return false;
  }
}

/** First-run checklist for a new owner. Each step ticks itself from the real data. */
function GettingStarted({ medicineCount, billCount, onOpenSettings, onAddMedicine, onNewSale, onOpenTeam, onOpenHelp }) {
  const [shop, setShop] = useState(null);
  const [staff, setStaff] = useState(1);
  const [hidden, setHidden] = useState(readHidden);

  useEffect(() => {
    if (hidden) return;

    fetch(api("/api/shop"))
      .then((response) => (response.ok ? response.json() : null))
      .then(setShop)
      .catch(() => {});

    fetch(api("/api/team"))
      .then((response) => (response.ok ? response.json() : null))
      .then((data) => {
        const members = Array.isArray(data) ? data : data?.members;
        if (Array.isArray(members)) setStaff(members.length);
      })
      .catch(() => {});
  }, [hidden]);

  if (hidden || !shop) return null;

  const steps = [
    {
      title: "Add your shop details",
      note: "GSTIN, address and licence print on every bill.",
      done: Boolean(shop.address && shop.phone),
      action: onOpenSettings,
      label: "Open settings",
    },
    {
      title: "Add your medicines",
      note: "Type them in or upload a CSV file.",
      done: medicineCount > 0,
      action: onAddMedicine,
      label: "Add medicines",
    },
    {
      title: "Make your first bill",
      note: "It takes under a minute.",
      done: billCount > 0,
      action: onNewSale,
      label: "New bill",
    },
    {
      title: "Add your staff (optional)",
      note: "Give each person their own login.",
      done: staff > 1,
      action: onOpenTeam,
      label: "Add staff",
    },
  ];

  const left = steps.filter((step) => !step.done).length;
  if (left === 0) return null;

  const hide = () => {
    try {
      localStorage.setItem(KEY, "1");
    } catch {
      /* the list simply shows again next time */
    }
    setHidden(true);
  };

  return (
    <section className="dashboard-card setup-card">
      <div className="card-header">
        <div>
          <h2>Set up your shop</h2>
          <p>{steps.length - left} of {steps.length} done</p>
        </div>
        <div className="setup-links">
          <button type="button" className="view-button" onClick={onOpenHelp}>Help</button>
          <button type="button" className="view-button" onClick={hide}>Hide</button>
        </div>
      </div>

      <ol className="setup-steps">
        {steps.map((step) => (
          <li key={step.title} className={step.done ? "done" : ""}>
            <span className="setup-tick" aria-hidden="true">
              {step.done ? <Icon name="check" size={15} /> : null}
            </span>
            <div>
              <strong>{step.title}</strong>
              <small>{step.note}</small>
            </div>
            {!step.done && (
              <button type="button" className="secondary-button" onClick={step.action}>
                {step.label}
              </button>
            )}
          </li>
        ))}
      </ol>
    </section>
  );
}

export default GettingStarted;
