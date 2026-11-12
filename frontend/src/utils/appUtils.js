import { useEffect } from "react";
import { api } from "../config.js";

export const API_URL = api("/api/medicines");
export const SALES_URL = api("/api/sales");

export const DEFAULT_SETTINGS = {
  pharmacyName: "MedTrack",
  lowStockLimit: 10,
  expiryWarningDays: 30,
  currency: "₹",
  autoRefresh: false,
};

export function getAppSettings() {
  try {
    return {
      ...DEFAULT_SETTINGS,
      ...JSON.parse(localStorage.getItem("medtrackSettings") || "{}"),
    };
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

/** Keeps the name shown in the sidebar in step with the shop name saved on the server. */
export function syncPharmacyName(name) {
  if (!name) return;

  try {
    const current = JSON.parse(localStorage.getItem("medtrackSettings") || "{}");
    if (current.pharmacyName === name) return;

    localStorage.setItem(
      "medtrackSettings",
      JSON.stringify({ ...current, pharmacyName: name })
    );
    window.dispatchEvent(new Event("medtrack-settings-changed"));
  } catch {
    /* settings storage unavailable */
  }
}

export const CURRENCY_RATES_FROM_INR = {
  "₹": 1,
  "$": 1 / 96.33,
  "€": 1 / 108.92,
  "£": 1 / 127.30,
  "¥": 1 / 0.6124,
  "C$": 1 / 68.85,
  "A$": 1 / 67.19,
};

export function getCurrencySymbol() {
  return getAppSettings().currency || DEFAULT_SETTINGS.currency;
}

export function convertFromINR(value) {
  const amount = Number(value || 0);
  const currency = getCurrencySymbol();
  const rate = CURRENCY_RATES_FROM_INR[currency] || 1;
  return amount * rate;
}

export function formatMoney(value) {
  const convertedAmount = convertFromINR(value);
  return `${getCurrencySymbol()}${convertedAmount.toLocaleString("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

export function isLowStock(quantity) {
  return Number(quantity || 0) <= Number(getAppSettings().lowStockLimit);
}

export function useAutoRefresh(callback, enabled) {
  useEffect(() => {
    if (!enabled) return undefined;
    const timer = setInterval(() => callback(), 30000);
    return () => clearInterval(timer);
  }, [callback, enabled]);
}


export const authInputStyle = {
  width: "100%", boxSizing: "border-box", padding: "0 14px", height: "46px",
  marginBottom: "18px", border: "1px solid #dbe2ea", borderRadius: "10px",
  outline: "none", fontSize: "14px", background: "#fff", color: "#0f172a",
  transition: "border-color .15s, box-shadow .15s",
};
export const authLabelStyle = {
  display: "block", marginBottom: "7px", color: "#334155",
  fontSize: "13px", fontWeight: "600",
};
export const authButtonStyle = (loading) => ({
  width: "100%", border: "none", borderRadius: "10px", height: "46px", padding: "0 16px",
  background: loading ? "#9ca3af" : "#059669", color: "white", fontSize: "15px",
  fontWeight: "600", cursor: loading ? "not-allowed" : "pointer",
  transition: "background .15s, box-shadow .15s, transform .05s",
});
