import { useEffect, useState } from "react";
import Icon from "../layout/Icons.jsx";
import { api } from "../../config.js";
import { syncPharmacyName } from "../../utils/appUtils.js";

const EMPTY = {
  name: "",
  address: "",
  phone: "",
  gstin: "",
  drugLicense: "",
  state: "",
  invoicePrefix: "INV-",
  pricesIncludeGst: true,
};

/** Shop details that are printed on every GST invoice. Only the owner can change them. */
function ShopProfile() {
  const isOwner = localStorage.getItem("medtrackRole") === "owner";

  const [form, setForm] = useState(EMPTY);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState({ type: "", text: "" });

  useEffect(() => {
    let cancelled = false;

    fetch(api("/api/shop"))
      .then((response) => response.json().then((data) => ({ ok: response.ok, data })))
      .then(({ ok, data }) => {
        if (cancelled) return;
        if (!ok || data.success === false) {
          throw new Error(data.error || "Could not load shop details.");
        }
        setForm({ ...EMPTY, ...data });
      })
      .catch((error) => {
        if (!cancelled) setMessage({ type: "error", text: error.message });
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const change = (event) => {
    const { name, value } = event.target;
    setForm((current) => ({
      ...current,
      [name]: name === "gstin" ? value.toUpperCase() : value,
    }));
  };

  const save = async (event) => {
    event.preventDefault();
    setMessage({ type: "", text: "" });

    try {
      setSaving(true);

      const response = await fetch(api("/api/shop"), {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(form),
      });
      const data = await response.json().catch(() => ({}));

      if (!response.ok || data.success === false) {
        throw new Error(data.error || "Could not save shop details.");
      }

      setForm({ ...EMPTY, ...data });
      syncPharmacyName(data.name);
      setMessage({ type: "success", text: "Shop details saved. New bills will use them." });
    } catch (error) {
      setMessage({ type: "error", text: error.message });
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="settings-card">
      <div className="settings-section-title">
        <div className="settings-icon">
          <Icon name="receipt" size={20} />
        </div>
        <div>
          <h3>Shop &amp; GST details</h3>
          <p>
            Printed on every invoice.{" "}
            {isOwner ? "Check these with your accountant." : "Only the owner can change these."}
          </p>
        </div>
      </div>

      {message.text && (
        <div className={`shop-message ${message.type}`} role="status">
          {message.text}
        </div>
      )}

      {loading ? (
        <p className="shop-loading">Loading shop details...</p>
      ) : (
        <form onSubmit={save}>
          <div className="settings-grid">
            <label className="settings-field">
              <span>Shop name</span>
              <input
                name="name"
                value={form.name}
                onChange={change}
                maxLength={80}
                required
                disabled={!isOwner}
              />
            </label>

            <label className="settings-field">
              <span>Phone</span>
              <input
                name="phone"
                value={form.phone}
                onChange={change}
                maxLength={20}
                placeholder="+91 98765 43210"
                disabled={!isOwner}
              />
            </label>

            <label className="settings-field shop-wide">
              <span>Address</span>
              <input
                name="address"
                value={form.address}
                onChange={change}
                maxLength={200}
                placeholder="Shop no., street, city, PIN"
                disabled={!isOwner}
              />
            </label>

            <label className="settings-field">
              <span>GSTIN</span>
              <input
                name="gstin"
                value={form.gstin}
                onChange={change}
                maxLength={15}
                placeholder="27ABCDE1234F1Z5"
                disabled={!isOwner}
              />
              <small>15 characters. Leave empty if you are not GST registered.</small>
            </label>

            <label className="settings-field">
              <span>Drug licence no.</span>
              <input
                name="drugLicense"
                value={form.drugLicense}
                onChange={change}
                maxLength={60}
                disabled={!isOwner}
              />
            </label>

            <label className="settings-field">
              <span>State</span>
              <input
                name="state"
                value={form.state}
                onChange={change}
                maxLength={40}
                placeholder="Maharashtra"
                disabled={!isOwner}
              />
              <small>Bills are CGST + SGST (sales within this state).</small>
            </label>

            <label className="settings-field">
              <span>Invoice number prefix</span>
              <input
                name="invoicePrefix"
                value={form.invoicePrefix}
                onChange={change}
                maxLength={10}
                placeholder="INV-"
                disabled={!isOwner}
              />
              <small>Bills are numbered like {form.invoicePrefix || ""}00001, 00002...</small>
            </label>
          </div>

          <div className="setting-toggle-row shop-toggle">
            <div>
              <strong>Medicine prices already include GST</strong>
              <span>
                On (usual for MRP): GST is taken out of the price. Off: GST is added on top.
              </span>
            </div>
            <button
              type="button"
              className={`toggle ${form.pricesIncludeGst ? "on" : ""}`}
              role="switch"
              aria-checked={form.pricesIncludeGst}
              aria-label="Prices include GST"
              disabled={!isOwner}
              onClick={() =>
                setForm((current) => ({ ...current, pricesIncludeGst: !current.pricesIncludeGst }))
              }
            >
              <span />
            </button>
          </div>

          {isOwner && (
            <div className="shop-actions">
              <button type="submit" className="primary-button" disabled={saving}>
                {saving ? "Saving..." : "Save shop details"}
              </button>
            </div>
          )}
        </form>
      )}
    </section>
  );
}

export default ShopProfile;
