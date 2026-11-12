import { useCallback, useEffect, useState } from "react";
import "../business.css";
import { api } from "../../config.js";

/** "You are offline" and "your plan ends soon" strips at the top of the app. */
function StatusBanners({ isOwner, onOpenPlan }) {
  const [online, setOnline] = useState(() => navigator.onLine);
  const [plan, setPlan] = useState(null);

  const loadPlan = useCallback(() => {
    fetch(api("/api/plan"))
      .then((response) => (response.ok ? response.json() : null))
      .then((data) => data && setPlan(data))
      .catch(() => {});
  }, []);

  useEffect(() => {
    const goOnline = () => {
      setOnline(true);
      loadPlan();
    };
    const goOffline = () => setOnline(false);

    window.addEventListener("online", goOnline);
    window.addEventListener("offline", goOffline);
    window.addEventListener("medtrack-plan-changed", loadPlan);
    const timer = setTimeout(loadPlan, 0);

    return () => {
      clearTimeout(timer);
      window.removeEventListener("online", goOnline);
      window.removeEventListener("offline", goOffline);
      window.removeEventListener("medtrack-plan-changed", loadPlan);
    };
  }, [loadPlan]);

  const freeEnding = plan && plan.state === "free" && plan.endsOn && plan.daysLeft <= 30;

  const showPlan =
    plan &&
    (freeEnding ||
      plan.state === "expired" ||
      plan.state === "grace" ||
      (plan.state === "trial" && plan.daysLeft <= 5) ||
      (plan.state === "active" && plan.daysLeft <= 5));

  const planText = !plan
    ? ""
    : freeEnding
      ? `MedTrack is free for ${plan.daysLeft} more day${plan.daysLeft === 1 ? "" : "s"}. Paid plans start on ${new Date(`${plan.endsOn}T00:00:00`).toLocaleDateString("en-IN", { day: "2-digit", month: "long", year: "numeric" })}.`
      : plan.state === "expired"
      ? "Your plan has ended. Your data is safe, but billing and changes are paused until you renew."
      : plan.state === "grace"
        ? "Your plan has ended. You have a few extra days to renew before billing stops."
        : plan.daysLeft === 0
          ? "Your plan ends today."
          : `Your ${plan.state === "trial" ? "free trial" : "plan"} ends in ${plan.daysLeft} day${plan.daysLeft === 1 ? "" : "s"}.`;

  return (
    <>
      {!online && (
        <div className="status-banner offline" role="status">
          You are offline. You can look around, but bills are saved only when the internet is back.
        </div>
      )}

      {showPlan && (
        <div className={`status-banner ${plan.state === "expired" ? "ended" : "soon"}`} role="status">
          <span>{planText}</span>
          {isOwner ? (
            <button type="button" onClick={onOpenPlan}>{freeEnding ? "See plans" : "Renew"}</button>
          ) : (
            <small>Please tell the shop owner.</small>
          )}
        </div>
      )}
    </>
  );
}

export default StatusBanners;
