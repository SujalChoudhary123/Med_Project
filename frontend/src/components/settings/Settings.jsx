import Icon from "../layout/Icons.jsx";
import { useEffect, useState } from "react";
import { DEFAULT_SETTINGS } from "../../utils/appUtils.js";
import ShopProfile from "./ShopProfile.jsx";

function Settings() {

  const defaultSettings = DEFAULT_SETTINGS;

  const [settings, setSettings] = useState(() => {
    try {
      return {
        ...defaultSettings,
        ...JSON.parse(
          localStorage.getItem("medtrackSettings") || "{}"
        ),
      };
    } catch {
      return defaultSettings;
    }
  });

  useEffect(() => {
    // Settings are persisted automatically whenever any value changes.
    // The shop name now lives in "Shop & GST details" (saved on the server),
    // so keep whatever name is already stored instead of overwriting it.
    let stored;
    try {
      stored = JSON.parse(localStorage.getItem("medtrackSettings") || "{}");
    } catch {
      stored = {};
    }

    localStorage.setItem(
      "medtrackSettings",
      JSON.stringify({
        ...settings,
        pharmacyName: stored.pharmacyName ?? settings.pharmacyName,
      })
    );

    window.dispatchEvent(
      new Event("medtrack-settings-changed")
    );
  }, [settings]);

  const updateSetting = (key, value) => {
    setSettings((current) => ({
      ...current,
      [key]: value,
    }));
  };

  const resetSettings = () => {
    setSettings({ ...defaultSettings });
  };

  return (
    <div className="page-scroll settings-page">

      {/* HEADER */}

      <div className="settings-heading">

        <div>
          <h2>Settings</h2>

          <p>
            Manage your pharmacy and application settings.
          </p>
        </div>

        <div className="save-message">
          ✓ Changes are saved automatically
        </div>

      </div>

      {/* SHOP + GST DETAILS (printed on invoices) */}

      <ShopProfile />

      {/* GENERAL SETTINGS */}

      <section className="settings-card">

        <div className="settings-section-title">

          <div className="settings-icon">
            <Icon name="settings" size={20} />
          </div>

          <div>
            <h3>General</h3>

            <p>
              Basic information about your pharmacy.
            </p>
          </div>

        </div>

        <div className="settings-grid">

          <label className="settings-field">

            <span>
              Currency
            </span>

            <select
              value={settings.currency}
              onChange={(e) =>
                updateSetting(
                  "currency",
                  e.target.value
                )
              }
            >

              <option value="₹">
                ₹ — Indian Rupee
              </option>

              <option value="$">
                $ — US Dollar
              </option>

              <option value="€">
                € — Euro
              </option>

              <option value="£">
                £ — Pound
              </option>

              <option value="¥">
                ¥ — Japanese Yen
              </option>

              <option value="C$">
                C$ — Canadian Dollar
              </option>

              <option value="A$">
                A$ — Australian Dollar
              </option>

            </select>

          </label>

        </div>

      </section>

      {/* INVENTORY SETTINGS */}

      <section className="settings-card">

        <div className="settings-section-title">

          <div className="settings-icon">
            <Icon name="alert" size={20} />
          </div>

          <div>
            <h3>Inventory Alerts</h3>

            <p>
              Control when medicines are marked as requiring attention.
            </p>
          </div>

        </div>

        <div className="settings-grid">

          <label className="settings-field">

            <span>
              Low Stock Threshold
            </span>

            <input
              type="number"
              min="0"
              value={settings.lowStockLimit}
              onChange={(e) =>
                updateSetting(
                  "lowStockLimit",
                  e.target.value
                )
              }
            />

            <small>
              Medicines at or below this quantity will be marked as low stock.
            </small>

          </label>

          <label className="settings-field">

            <span>
              Expiry Warning Period
            </span>

            <input
              type="number"
              min="1"
              value={settings.expiryWarningDays}
              onChange={(e) =>
                updateSetting(
                  "expiryWarningDays",
                  e.target.value
                )
              }
            />

            <small>
              Medicines expiring within this many days will be highlighted.
            </small>

          </label>

        </div>

      </section>

      {/* APPLICATION */}

      <section className="settings-card">

        <div className="settings-section-title">

          <div className="settings-icon">
            <Icon name="clock" size={20} />
          </div>

          <div>
            <h3>Application</h3>

            <p>
              Control automatic data updates.
            </p>
          </div>

        </div>

        <div className="setting-toggle-row">

          <div>

            <strong>
              Auto Refresh Data
            </strong>

            <span>
              Automatically refresh dashboard data when enabled.
            </span>

          </div>

          <button
            type="button"
            className={`toggle ${
              settings.autoRefresh ? "on" : ""
            }`}
            onClick={() =>
              updateSetting(
                "autoRefresh",
                !settings.autoRefresh
              )
            }
            aria-label="Toggle auto refresh"
          >
            <span />
          </button>

        </div>

      </section>

      {/* ABOUT */}

      <section className="settings-card">

        <div className="settings-section-title">

          <div className="settings-icon">
            <Icon name="info" size={20} />
          </div>

          <div>
            <h3>About</h3>

            <p>
              Medicine Management System
            </p>
          </div>

        </div>

        <div className="about-grid">

          <div>
            <span>
              Frontend
            </span>

            <strong>
              React + Vite
            </strong>
          </div>

          <div>
            <span>
              Backend
            </span>

            <strong>
              Java HTTP API
            </strong>
          </div>

          <div>
            <span>
              Database
            </span>

            <strong>
              SQLite
            </strong>
          </div>

          <div>
            <span>
              Version
            </span>

            <strong>
              1.0
            </strong>
          </div>

        </div>

      </section>

      {/* RESET */}

      <div className="settings-actions">

        <button
          className="secondary-button"
          onClick={resetSettings}
        >
          Reset Defaults
        </button>

      </div>

    </div>
  );
}

/* =========================================================
   PLACEHOLDER
========================================================= */

export default Settings;
