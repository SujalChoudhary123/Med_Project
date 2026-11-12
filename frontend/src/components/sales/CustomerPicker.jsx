import { useEffect, useRef, useState } from "react";
import { api } from "../../config.js";
import { inr } from "../../utils/invoiceUtils.js";

async function readJson(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok || data.success === false) {
    throw new Error(data.error || "Something went wrong. Please try again.");
  }
  return data;
}

/**
 * Pick a saved customer for the bill (needed for udhaar / credit), or add a
 * new one on the spot. Walk-in customers need nothing here.
 */
function CustomerPicker({ picked, onPick }) {
  const [text, setText] = useState("");
  const [results, setResults] = useState([]);
  const [open, setOpen] = useState(false);
  const [adding, setAdding] = useState(false);
  const [form, setForm] = useState({ name: "", phone: "" });
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const ticket = useRef(0);

  useEffect(() => {
    if (!open) return undefined;
    const mine = ++ticket.current;

    const timer = setTimeout(async () => {
      try {
        const data = await fetch(
          api(`/api/customers?limit=8&search=${encodeURIComponent(text.trim())}`)
        ).then(readJson);
        if (mine === ticket.current) setResults(data.customers || []);
      } catch {
        if (mine === ticket.current) setResults([]);
      }
    }, 200);

    return () => clearTimeout(timer);
  }, [text, open]);

  const choose = (customer) => {
    onPick(customer);
    setText("");
    setOpen(false);
    setAdding(false);
  };

  const saveNew = async (event) => {
    event.preventDefault();
    setError("");

    if (!form.name.trim()) {
      setError("Enter the customer's name.");
      return;
    }

    try {
      setBusy(true);
      const created = await fetch(api("/api/customers"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: form.name, phone: form.phone }),
      }).then(readJson);

      choose({
        id: created.id,
        name: form.name.trim(),
        phone: form.phone.trim(),
        creditLimit: 0,
        balance: 0,
      });
      setForm({ name: "", phone: "" });
    } catch (problem) {
      setError(problem.message);
    } finally {
      setBusy(false);
    }
  };

  if (picked) {
    const limit = Number(picked.creditLimit) || 0;
    const owes = Number(picked.balance) || 0;

    return (
      <div className="cust-picked">
        <div>
          <strong>{picked.name}</strong>
          <small>{picked.phone || "No phone saved"}</small>
          <small className={owes > 0 ? "bad" : ""}>
            {owes > 0 ? `Already owes ${inr(owes)}` : owes < 0 ? `Advance ${inr(-owes)}` : "No dues"}
            {limit > 0 ? ` · limit ${inr(limit)}` : ""}
          </small>
        </div>
        <button type="button" className="view-button" onClick={() => onPick(null)}>
          Change
        </button>
      </div>
    );
  }

  return (
    <div className="cust-picker">
      <label>
        Saved customer (for udhaar)
        <input
          type="search"
          value={text}
          placeholder="Search name or phone"
          autoComplete="off"
          onChange={(event) => {
            setText(event.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 150)}
        />
      </label>

      {open && !adding && (
        <ul className="cust-results">
          {results.map((customer) => (
            <li key={customer.id}>
              <button type="button" onMouseDown={(event) => event.preventDefault()} onClick={() => choose(customer)}>
                <span>
                  <strong>{customer.name}</strong>
                  <small>{customer.phone || "-"}</small>
                </span>
                {customer.balance > 0 && <em>owes {inr(customer.balance)}</em>}
              </button>
            </li>
          ))}
          {results.length === 0 && <li className="note">No saved customer found</li>}
          <li>
            <button
              type="button"
              className="add"
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => {
                setForm({ name: text.trim(), phone: "" });
                setAdding(true);
              }}
            >
              + Add a new customer
            </button>
          </li>
        </ul>
      )}

      {adding && (
        <form className="cust-new" onSubmit={saveNew}>
          <input
            value={form.name}
            placeholder="Customer name"
            maxLength={80}
            onChange={(event) => setForm({ ...form, name: event.target.value })}
          />
          <input
            value={form.phone}
            placeholder="Phone (optional)"
            maxLength={20}
            inputMode="tel"
            onChange={(event) => setForm({ ...form, phone: event.target.value })}
          />
          {error && <p className="bad">{error}</p>}
          <div>
            <button type="button" className="secondary-button" onClick={() => setAdding(false)}>
              Cancel
            </button>
            <button type="submit" className="primary-button" disabled={busy}>
              {busy ? "Saving..." : "Save & use"}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

export default CustomerPicker;
