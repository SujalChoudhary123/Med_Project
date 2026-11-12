// Helpers for GST billing. Invoices are always in rupees (India GST), whatever
// display currency is chosen in Settings.

export const inr = (value) =>
  `₹${Number(value || 0).toLocaleString("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;

// Round to 2 decimals, halves up (same rule the server uses).
export const r2 = (value) => Math.round((value + Number.EPSILON) * 100) / 100;

/**
 * Live preview of one bill line while the cashier builds the bill.
 * The server repeats this calculation with exact decimals when the bill is
 * saved, and the saved invoice is what gets printed.
 */
export function computeLine({
  price,
  quantity,
  discountPercent = 0,
  gstRate = 0,
  inclusive = true,
}) {
  const gross = r2(price * quantity);
  const discount = r2((gross * discountPercent) / 100);
  const base = r2(gross - discount);

  let taxable;
  let gst;
  let lineTotal;

  if (inclusive) {
    lineTotal = base;
    taxable = r2((base * 100) / (100 + gstRate));
    gst = r2(lineTotal - taxable);
  } else {
    taxable = base;
    gst = r2((taxable * gstRate) / 100);
    lineTotal = r2(taxable + gst);
  }

  const cgst = r2(gst / 2);
  const sgst = r2(gst - cgst);

  return { gross, discount, taxable, cgst, sgst, lineTotal };
}

const ONES = [
  "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
  "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
  "Seventeen", "Eighteen", "Nineteen",
];
const TENS = ["", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"];

function belowHundred(n) {
  if (n < 20) return ONES[n];
  return `${TENS[Math.floor(n / 10)]}${n % 10 ? ` ${ONES[n % 10]}` : ""}`;
}

function belowThousand(n) {
  const hundreds = Math.floor(n / 100);
  const rest = n % 100;
  return [hundreds ? `${ONES[hundreds]} Hundred` : "", rest ? belowHundred(rest) : ""]
    .filter(Boolean)
    .join(" ");
}

/** 481 -> "Four Hundred Eighty One Rupees Only" (Indian crore/lakh system). */
export function amountInWords(amount) {
  const totalPaise = Math.round(Number(amount || 0) * 100);
  const rupees = Math.floor(totalPaise / 100);
  const paise = totalPaise % 100;

  if (rupees === 0 && paise === 0) return "Zero Rupees Only";

  const parts = [];
  let rest = rupees;

  const crore = Math.floor(rest / 10000000);
  rest %= 10000000;
  const lakh = Math.floor(rest / 100000);
  rest %= 100000;
  const thousand = Math.floor(rest / 1000);
  rest %= 1000;

  if (crore) parts.push(`${belowHundred(crore % 100) || belowThousand(crore)} Crore`);
  if (lakh) parts.push(`${belowHundred(lakh)} Lakh`);
  if (thousand) parts.push(`${belowHundred(thousand)} Thousand`);
  if (rest) parts.push(belowThousand(rest));

  let words = rupees ? `${parts.join(" ")} Rupees` : "";

  if (paise) {
    words += `${words ? " and " : ""}${belowHundred(paise)} Paise`;
  }

  return `${words} Only`;
}

/** "2026-11-03 14:05:09" -> "03 Nov 2026, 02:05 pm" */
export function formatInvoiceDate(value) {
  if (!value) return "";
  const date = new Date(String(value).replace(" ", "T"));
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** Opens a WhatsApp chat. A 10 digit Indian number gets 91 in front. Empty phone = choose the chat. */
export function whatsappLink(phone, text) {
  let digits = String(phone || "").replace(/\D/g, "");
  if (digits.startsWith("0")) digits = digits.replace(/^0+/, "");
  if (digits.length === 10) digits = `91${digits}`;
  const message = encodeURIComponent(text);
  return digits.length >= 11
    ? `https://wa.me/${digits}?text=${message}`
    : `https://wa.me/?text=${message}`;
}

/** The bill as a WhatsApp message. */
export function billMessage(invoice) {
  const lines = invoice.items
    .slice(0, 15)
    .map((item) => `${item.name} x ${item.quantity} = ${inr(item.lineTotal)}`);

  if (invoice.items.length > 15) {
    lines.push(`...and ${invoice.items.length - 15} more items`);
  }

  const due =
    invoice.paymentMode === "credit"
      ? Math.max(0, invoice.totals.total - invoice.amountPaid)
      : 0;

  return [
    invoice.seller.name,
    `Bill ${invoice.invoiceNo} | ${formatInvoiceDate(invoice.date)}`,
    "",
    ...lines,
    "",
    `Total: ${inr(invoice.totals.total)}`,
    ...(due > 0 ? [`Balance due: ${inr(due)}`] : []),
    "",
    "Thank you!",
  ].join("\n");
}

export const PAYMENT_MODES = [
  { value: "cash", label: "Cash" },
  { value: "upi", label: "UPI" },
  { value: "card", label: "Card" },
  { value: "credit", label: "Credit" },
];

/** "upi" -> "UPI", "bank" -> "Bank transfer": how a payment mode is shown to people. */
export function modeLabel(mode) {
  if (mode === "bank") return "Bank transfer";
  const known = PAYMENT_MODES.find((item) => item.value === mode);
  return known ? known.label : mode || "";
}