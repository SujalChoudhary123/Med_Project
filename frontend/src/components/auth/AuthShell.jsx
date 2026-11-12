import "./auth.css";
import Icon from "../layout/Icons.jsx";

const FEATURES = [
  { icon: "pill", text: "Live inventory with batch and expiry tracking" },
  { icon: "receipt", text: "Fast billing with automatic stock updates" },
  { icon: "bell", text: "Low-stock and expiry alerts before they cost you" },
  { icon: "shield", text: "Your pharmacy's data stays private to your shop" },
];

function AuthShell({
  children,
  title = "Welcome back",
  subtitle = "Sign in to manage your pharmacy",
  hideHeading = false,
}) {
  return (
    <div className="auth-page">
      <aside className="auth-brand">
        <div className="auth-brand-top">
          <span className="auth-logo">
            <Icon name="pill" size={22} />
          </span>
          <span className="auth-brand-name">MedTrack</span>
        </div>

        <div className="auth-brand-copy">
          <h2>Run your pharmacy with confidence.</h2>
          <p>
            Track stock, bill customers and stay ahead of expiry dates, all in
            one simple workspace.
          </p>

          <ul className="auth-features">
            {FEATURES.map((feature) => (
              <li key={feature.text}>
                <span className="auth-feature-icon">
                  <Icon name={feature.icon} size={16} />
                </span>
                {feature.text}
              </li>
            ))}
          </ul>
        </div>

        <div className="auth-brand-foot">
          &copy; {new Date().getFullYear()} MedTrack. All rights reserved.
        </div>
      </aside>

      <main className="auth-panel">
        <div className="auth-card">
          <div className="auth-mobile-brand">
            <span className="auth-logo">
              <Icon name="pill" size={20} />
            </span>
            <span className="auth-brand-name">MedTrack</span>
          </div>

          {!hideHeading && (
            <div className="auth-heading">
              <h1>{title}</h1>
              <p>{subtitle}</p>
            </div>
          )}

          {children}
        </div>
      </main>
    </div>
  );
}

export default AuthShell;
