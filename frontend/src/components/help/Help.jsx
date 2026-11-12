import { useEffect, useState } from "react";
import "../business.css";
import { api } from "../../config.js";

const GUIDE = {
  en: [
    {
      title: "Getting started",
      steps: [
        "Open Settings and fill in your shop name, address, phone, GSTIN and drug licence. These print on every bill.",
        "Add medicines one by one, or upload a CSV file from Add Medicine.",
        "Add your staff from Team. A cashier can only bill; a pharmacist can also manage stock.",
      ],
    },
    {
      title: "Making a bill",
      steps: [
        "Open Sales & Billing and search a medicine by name or ID. Add as many as needed.",
        "Choose Cash, UPI, Card or Credit, then press Generate bill.",
        "Print the bill, or send it on WhatsApp from the bill screen.",
        "If the internet drops, your bill stays on screen. Press Generate bill again when it returns. It can never be saved twice.",
      ],
    },
    {
      title: "Udhaar (credit)",
      steps: [
        "Choose or add a customer on the bill, then choose Credit. Enter anything they paid now.",
        "Open Customers to see who owes what. Use Receive payment when money comes in.",
        "Send a WhatsApp reminder or print a statement from the customer's page.",
        "The owner can set a credit limit. A bill above the limit is refused.",
      ],
    },
    {
      title: "Stock coming in",
      steps: [
        "Open Purchases and add your supplier once.",
        "Enter each delivery from the supplier's bill: batch, expiry, quantity, free units, rate and MRP.",
        "A new batch becomes its own stock line, so its expiry stays correct.",
        "Pay suppliers from the Suppliers tab. Cost prices are never shown to cashiers.",
      ],
    },
    {
      title: "Returns",
      steps: [
        "Open the bill and press Return items. Choose quantities and the reason.",
        "A credit note is made automatically. Damaged goods can be kept out of stock.",
        "On an udhaar bill, the refund is taken off what the customer owes.",
      ],
    },
    {
      title: "GST and profit",
      steps: [
        "Reports shows sales, GST by rate, HSN summary, input GST on purchases and profit.",
        "Download the CSV files for your accountant. Please have them checked before filing a return.",
        "Profit needs a cost price. Enter purchases, or add the cost on the medicine.",
      ],
    },
    {
      title: "Keeping your data safe",
      steps: [
        "The owner can see every action in Activity, and download all data from there.",
        "Keep a copy every month. Your data is yours, even after a plan ends.",
        "Each person should have their own login. Never share passwords.",
      ],
    },
  ],
  hi: [
    {
      title: "शुरुआत कैसे करें",
      steps: [
        "Settings खोलें और दुकान का नाम, पता, फ़ोन, GSTIN और ड्रग लाइसेंस भरें। ये हर बिल पर छपते हैं।",
        "दवाइयाँ एक-एक करके जोड़ें, या Add Medicine से CSV फ़ाइल अपलोड करें।",
        "Team से अपने स्टाफ़ को जोड़ें। कैशियर सिर्फ़ बिल बना सकता है, फ़ार्मासिस्ट स्टॉक भी संभाल सकता है।",
      ],
    },
    {
      title: "बिल कैसे बनाएँ",
      steps: [
        "Sales & Billing खोलें और दवा का नाम या ID खोजें। जितनी चाहें उतनी दवाइयाँ जोड़ें।",
        "Cash, UPI, Card या Credit चुनें और Generate bill दबाएँ।",
        "बिल प्रिंट करें, या बिल स्क्रीन से WhatsApp पर भेजें।",
        "अगर इंटरनेट चला जाए तो बिल स्क्रीन पर ही रहता है। इंटरनेट आने पर Generate bill फिर से दबाएँ। बिल दो बार नहीं बनेगा।",
      ],
    },
    {
      title: "उधार",
      steps: [
        "बिल पर ग्राहक चुनें या नया जोड़ें, फिर Credit चुनें। अगर अभी कुछ पैसे मिले हैं तो वह रकम भरें।",
        "Customers में देखें किस पर कितना उधार है। पैसे मिलने पर Receive payment दबाएँ।",
        "ग्राहक के पेज से WhatsApp रिमाइंडर भेजें या स्टेटमेंट प्रिंट करें।",
        "मालिक क्रेडिट लिमिट तय कर सकता है। लिमिट से ज़्यादा का बिल नहीं बनेगा।",
      ],
    },
    {
      title: "माल की ख़रीद (स्टॉक आना)",
      steps: [
        "Purchases खोलें और सप्लायर को एक बार जोड़ लें।",
        "सप्लायर के बिल से हर माल भरें: बैच, एक्सपायरी, मात्रा, फ़्री यूनिट, रेट और MRP।",
        "नया बैच अलग लाइन में रहता है, ताकि उसकी एक्सपायरी सही रहे।",
        "Suppliers टैब से सप्लायर को भुगतान दर्ज करें। कैशियर को ख़रीद का रेट कभी नहीं दिखता।",
      ],
    },
    {
      title: "माल वापसी",
      steps: [
        "बिल खोलकर Return items दबाएँ। मात्रा और कारण चुनें।",
        "क्रेडिट नोट अपने आप बन जाता है। ख़राब माल को स्टॉक से बाहर रखा जा सकता है।",
        "उधार के बिल पर वापसी की रकम ग्राहक के उधार से घट जाती है।",
      ],
    },
    {
      title: "GST और मुनाफ़ा",
      steps: [
        "Reports में बिक्री, रेट के हिसाब से GST, HSN सारांश, ख़रीद पर इनपुट GST और मुनाफ़ा दिखता है।",
        "अपने अकाउंटेंट के लिए CSV फ़ाइलें डाउनलोड करें। रिटर्न भरने से पहले उनसे जाँच करवा लें।",
        "मुनाफ़े के लिए ख़रीद रेट ज़रूरी है। Purchases भरें, या दवा पर रेट डालें।",
      ],
    },
    {
      title: "डेटा की सुरक्षा",
      steps: [
        "मालिक Activity में हर काम देख सकता है और वहीं से पूरा डेटा डाउनलोड कर सकता है।",
        "हर महीने एक कॉपी रखें। प्लान ख़त्म होने के बाद भी आपका डेटा आपका ही है।",
        "हर व्यक्ति का अपना लॉगिन हो। पासवर्ड किसी से साझा न करें।",
      ],
    },
  ],
};

function Help() {
  const [language, setLanguage] = useState("en");
  const [support, setSupport] = useState({ supportPhone: "", supportEmail: "" });

  useEffect(() => {
    fetch(api("/api/public"))
      .then((response) => (response.ok ? response.json() : null))
      .then((data) => data && setSupport(data))
      .catch(() => {});
  }, []);

  return (
    <div className="bill-page help-page">
      <section className="dashboard-card">
        <div className="card-header">
          <div>
            <h2>{language === "en" ? "How to use MedTrack" : "MedTrack कैसे चलाएँ"}</h2>
            <p>{language === "en" ? "Short steps for everyday work" : "रोज़ के काम के आसान तरीक़े"}</p>
          </div>
          <div className="help-language" role="radiogroup" aria-label="Language">
            <button type="button" role="radio" aria-checked={language === "en"} className={language === "en" ? "active" : ""} onClick={() => setLanguage("en")}>English</button>
            <button type="button" role="radio" aria-checked={language === "hi"} className={language === "hi" ? "active" : ""} onClick={() => setLanguage("hi")}>हिंदी</button>
          </div>
        </div>
      </section>

      <div className="help-grid">
        {GUIDE[language].map((section) => (
          <section className="dashboard-card" key={section.title}>
            <h3>{section.title}</h3>
            <ol>
              {section.steps.map((step) => <li key={step}>{step}</li>)}
            </ol>
          </section>
        ))}
      </div>

      {(support.supportPhone || support.supportEmail) && (
        <section className="dashboard-card">
          <h3>{language === "en" ? "Need help?" : "मदद चाहिए?"}</h3>
          <p className="help-support">
            {support.supportPhone && <a href={`tel:${support.supportPhone}`}>{support.supportPhone}</a>}
            {support.supportEmail && <a href={`mailto:${support.supportEmail}`}>{support.supportEmail}</a>}
          </p>
        </section>
      )}
    </div>
  );
}

export default Help;
