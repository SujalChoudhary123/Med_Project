import { useState } from "react";
import { API_URL } from "../../utils/appUtils.js";
import BulkResultBox from "./BulkResultBox.jsx";
import FormField from "./FormField.jsx";
import GstFields from "./GstFields.jsx";

function AddMedicine({
  onCancel,
  onSuccess,
}) {

  const [formData, setFormData] =
    useState({
      id: "",
      name: "",
      batchNumber: "",
      category: "",
      quantity: "",
      price: "",
      manufacturer: "",
      disease: "",
      mfgDate: "",
      expiryDate: "",
      gstRate: "5",
      hsn: "3004",
      costPrice: "",
    });

  const [loading, setLoading] = useState(false);

  const [bulkLoading, setBulkLoading] =
    useState(false);

  const [error, setError] =
    useState("");

  const [success, setSuccess] =
    useState("");

  const [bulkResult, setBulkResult] =
    useState(null);


  /* =====================================================
     HANDLE INPUT CHANGE
  ===================================================== */

  const handleChange = (e) => {

    const {
      name,
      value,
    } = e.target;


    setFormData(
      (previous) => ({
        ...previous,
        [name]: value,
      })
    );


    setError("");
    setSuccess("");
  };


  /* =====================================================
     CSV LINE PARSER
  ===================================================== */

  const parseCSVLine = (line) => {

    const values = [];

    let current = "";

    let insideQuotes = false;


    for (
      let i = 0;
      i < line.length;
      i++
    ) {

      const char = line[i];

      const nextChar =
        line[i + 1];


      if (char === '"') {

        if (
          insideQuotes &&
          nextChar === '"'
        ) {

          current += '"';

          i++;

        } else {

          insideQuotes =
            !insideQuotes;
        }

      } else if (
        char === "," &&
        !insideQuotes
      ) {

        values.push(
          current.trim()
        );

        current = "";

      } else {

        current += char;
      }
    }


    if (insideQuotes) {

      throw new Error(
        "Unclosed quote found in CSV row."
      );
    }


    values.push(
      current.trim()
    );


    return values;
  };


  /* =====================================================
     CSV FILE PARSER
  ===================================================== */

  const parseCSVText = (text) => {

    const cleanText =
      text
        .replace(/^\uFEFF/, "")
        .replace(/\r\n/g, "\n")
        .replace(/\r/g, "\n");


    const lines =
      cleanText
        .split("\n")
        .filter(
          (line) =>
            line.trim() !== ""
        );


    if (lines.length === 0) {

      throw new Error(
        "The CSV file is empty."
      );
    }


    const expectedHeader = [
      "id",
      "name",
      "batchNumber",
      "category",
      "quantity",
      "price",
      "manufacturer",
      "disease",
      "mfgDate",
      "expiryDate",
    ];


    const header =
      parseCSVLine(
        lines[0]
      ).map(
        (value) =>
          value
            .trim()
            .toLowerCase()
      );


    // Two optional columns may follow expiryDate: gstRate and hsn.
    // Older 10-column files keep working (those medicines get 5% / 3004).
    const normalizedExpected =
      [
        ...expectedHeader,
        "gstRate",
        "hsn",
      ].map(
        (value) =>
          value.toLowerCase()
      );


    if (
      (header.length !== 10 &&
        header.length !== 12) ||
      header.some(
        (value, index) =>
          value !==
          normalizedExpected[index]
      )
    ) {

      throw new Error(
        `Invalid CSV header. Expected: ${expectedHeader.join(",")} (optionally followed by gstRate,hsn)`
      );
    }


    const rows = [];

    const errors = [];

    const idsInFile =
      new Set();


    for (
      let index = 1;
      index < lines.length;
      index++
    ) {

      const csvLineNumber =
        index + 1;


      let values;


      try {

        values =
          parseCSVLine(
            lines[index]
          );

      } catch (err) {

        errors.push(
          `Row ${csvLineNumber}: ${err.message}`
        );

        continue;
      }


      if (
        values.length !==
        header.length
      ) {

        errors.push(
          `Row ${csvLineNumber}: Expected ${header.length} columns but found ${values.length}.`
        );

        continue;
      }


      const [
        id,
        name,
        batchNumber,
        category,
        quantityText,
        priceText,
        manufacturer,
        disease,
        mfgDate,
        expiryDate,
        gstRateText = "",
        hsnText = "",
      ] = values;


      /* =============================================
         REQUIRED FIELD VALIDATION
      ============================================= */

      if (
        !id ||
        !name ||
        !batchNumber ||
        !category ||
        !quantityText ||
        !priceText ||
        !manufacturer ||
        !disease ||
        !mfgDate ||
        !expiryDate
      ) {

        errors.push(
          `Row ${csvLineNumber}: All 10 fields are required.`
        );

        continue;
      }


      /* =============================================
         DUPLICATE ID INSIDE CSV
      ============================================= */

      if (
        idsInFile.has(
          id.toLowerCase()
        )
      ) {

        errors.push(
          `Row ${csvLineNumber}: Duplicate Medicine ID "${id}" in this CSV file.`
        );

        continue;
      }


      /* =============================================
         NUMBER VALIDATION
      ============================================= */

      const quantity =
        Number(quantityText);

      const price =
        Number(priceText);


      if (
        !Number.isInteger(
          quantity
        ) ||
        quantity < 0
      ) {

        errors.push(
          `Row ${csvLineNumber}: Quantity must be a whole number and cannot be negative.`
        );

        continue;
      }


      if (
        !Number.isFinite(price) ||
        price < 0
      ) {

        errors.push(
          `Row ${csvLineNumber}: Price must be a valid number and cannot be negative.`
        );

        continue;
      }


      /* =============================================
         DATE FORMAT VALIDATION
      ============================================= */

      if (
        !/^\d{4}-\d{2}-\d{2}$/.test(
          mfgDate
        ) ||
        !/^\d{4}-\d{2}-\d{2}$/.test(
          expiryDate
        )
      ) {

        errors.push(
          `Row ${csvLineNumber}: Dates must use YYYY-MM-DD format.`
        );

        continue;
      }


      /* =============================================
         EXPIRY DATE VALIDATION
      ============================================= */

      if (
        expiryDate <
        mfgDate
      ) {

        errors.push(
          `Row ${csvLineNumber}: Expiry date cannot be before manufacturing date.`
        );

        continue;
      }


      /* =============================================
         OPTIONAL GST RATE + HSN
      ============================================= */

      const gstRateValue =
        gstRateText.trim().replace(/%$/, "");

      const hsnValue =
        hsnText.trim();


      if (
        gstRateValue !== "" &&
        ![0, 5, 12, 18, 28].includes(
          Number(gstRateValue)
        )
      ) {

        errors.push(
          `Row ${csvLineNumber}: GST rate must be 0, 5, 12, 18 or 28.`
        );

        continue;
      }


      if (
        hsnValue !== "" &&
        !/^\d{4,8}$/.test(hsnValue)
      ) {

        errors.push(
          `Row ${csvLineNumber}: HSN code must be 4 to 8 digits.`
        );

        continue;
      }


      idsInFile.add(
        id.toLowerCase()
      );


      rows.push({
        lineNumber:
          csvLineNumber,

        medicine: {

          id,

          name,

          batchNumber,

          category,

          quantity,

          price,

          manufacturer,

          disease,

          mfgDate,

          expiryDate,

          // Left out when the column is empty/missing, so the server default applies.
          ...(gstRateValue !== ""
            ? { gstRate: gstRateValue }
            : {}),

          ...(hsnValue !== ""
            ? { hsn: hsnValue }
            : {}),
        },
      });
    }


    return {
      rows,
      errors,
    };
  };


  /* =====================================================
     DOWNLOAD CSV TEMPLATE
  ===================================================== */

  const handleDownloadTemplate =
    () => {

      const header =
        "id,name,batchNumber,category,quantity,price,manufacturer,disease,mfgDate,expiryDate,gstRate,hsn\n"
        + "MED001,Paracetamol 500mg,B2026A,Tablet,100,25.50,ABC Pharma,Fever,2026-01-01,2028-01-01,5,3004\n";


      const blob =
        new Blob(
          [header],
          {
            type:
              "text/csv;charset=utf-8;",
          }
        );


      const url =
        URL.createObjectURL(
          blob
        );


      const link =
        document.createElement(
          "a"
        );


      link.href = url;

      link.download =
        "medicine_inventory_template.csv";


      document.body.appendChild(
        link
      );

      link.click();

      document.body.removeChild(
        link
      );


      URL.revokeObjectURL(
        url
      );
    };


  /* =====================================================
     BULK CSV UPLOAD
  ===================================================== */

  const handleBulkUpload =
    () => {

      if (
        loading ||
        bulkLoading
      ) {

        return;
      }


      setError("");

      setSuccess("");

      setBulkResult(null);


      const input =
        document.createElement(
          "input"
        );


      input.type = "file";

      input.accept =
        ".csv,text/csv";


      input.onchange =
        async (event) => {

          const file =
            event.target
              .files?.[0];


          if (!file) {
            return;
          }


          if (
            !file.name
              .toLowerCase()
              .endsWith(".csv")
          ) {

            setError(
              "Please select a CSV file only."
            );

            return;
          }


          try {

            setBulkLoading(
              true
            );

            setError("");

            setSuccess("");

            setBulkResult(null);


            const text =
              await file.text();


            const {
              rows,
              errors:
                validationErrors,
            } =
              parseCSVText(
                text
              );


            if (
              rows.length === 0 &&
              validationErrors.length === 0
            ) {

              setError(
                "The CSV contains no medicine records."
              );

              return;
            }


            let imported = 0;


            const importErrors =
              [
                ...validationErrors,
              ];


            /* =========================================
               IMPORT VALID ROWS ONE BY ONE
            ========================================= */

            for (
              const row of rows
            ) {

              try {

                const response =
                  await fetch(
                    API_URL,
                    {
                      method:
                        "POST",

                      headers: {
                        "Content-Type":
                          "application/json",
                      },

                      body:
                        JSON.stringify(
                          row.medicine
                        ),
                    }
                  );


                const responseText =
                  await response.text();


                if (
                  !response.ok
                ) {

                  let message =
                    "Unable to import this medicine.";


                  try {

                    const data =
                      JSON.parse(
                        responseText
                      );


                    message =
                      data.error ||
                      data.message ||
                      message;

                  } catch {

                    if (
                      responseText.trim()
                    ) {

                      message =
                        responseText.trim();
                    }
                  }


                  if (
                    response.status ===
                    409
                  ) {

                    message =
                      "Medicine ID already exists.";
                  }


                  importErrors.push(
                    `Row ${row.lineNumber}: ${message}`
                  );


                  continue;
                }


                imported++;


              } catch (err) {

                importErrors.push(
                  `Row ${row.lineNumber}: ${
                    err.message ||
                    "Unable to connect to the Java API."
                  }`
                );
              }
            }


            /* =========================================
               FINAL RESULT
            ========================================= */

            setBulkResult({

              fileName:
                file.name,

              totalRows:
                rows.length +
                validationErrors.length,

              validRows:
                rows.length,

              imported,

              failed:
                importErrors.length,

              errors:
                importErrors,
            });


            if (
              imported > 0 &&
              importErrors.length === 0
            ) {

              setSuccess(
                `Bulk upload completed successfully. ${imported} medicine(s) imported.`
              );

            } else if (
              imported > 0
            ) {

              setSuccess(
                `Bulk upload completed with ${imported} medicine(s) imported and ${importErrors.length} row(s) failed.`
              );

            } else {

              setError(
                "No medicines were imported. Please check the errors below."
              );
            }


          } catch (err) {

            console.error(
              "Bulk CSV upload error:",
              err
            );


            setError(
              err.message ||
              "Unable to process the CSV file."
            );


          } finally {

            setBulkLoading(
              false
            );
          }
        };


      input.click();
    };


  /* =====================================================
     SINGLE MEDICINE SUBMIT
  ===================================================== */

  const handleSubmit =
    async (e) => {

      e.preventDefault();


      setError("");

      setSuccess("");

      setBulkResult(null);


      /* =============================================
         REQUIRED FIELD VALIDATION
      ============================================= */

      if (
        !formData.id.trim() ||
        !formData.name.trim() ||
        !formData.batchNumber.trim() ||
        !formData.category.trim() ||
        formData.quantity === "" ||
        formData.price === "" ||
        !formData.manufacturer.trim() ||
        !formData.disease.trim() ||
        !formData.mfgDate ||
        !formData.expiryDate
      ) {

        setError(
          "Please fill in all fields."
        );

        return;
      }


      const quantity =
        Number(
          formData.quantity
        );


      const price =
        Number(
          formData.price
        );


      if (
        !Number.isInteger(
          quantity
        ) ||
        quantity < 0
      ) {

        setError(
          "Quantity must be a whole number and cannot be negative."
        );

        return;
      }


      if (
        !Number.isFinite(
          price
        ) ||
        price < 0
      ) {

        setError(
          "Price must be a valid number and cannot be negative."
        );

        return;
      }


      if (
        formData.expiryDate <
        formData.mfgDate
      ) {

        setError(
          "Expiry date cannot be before manufacturing date."
        );

        return;
      }


      try {

        setLoading(true);


        const response =
          await fetch(
            API_URL,
            {
              method:
                "POST",

              headers: {
                "Content-Type":
                  "application/json",
              },

              body:
                JSON.stringify({

                  id:
                    formData.id.trim(),

                  name:
                    formData.name.trim(),

                  batchNumber:
                    formData.batchNumber.trim(),

                  category:
                    formData.category.trim(),

                  quantity,

                  price,

                  manufacturer:
                    formData.manufacturer.trim(),

                  disease:
                    formData.disease.trim(),

                  mfgDate:
                    formData.mfgDate,

                  expiryDate:
                    formData.expiryDate,

                  gstRate:
                    formData.gstRate,

                  hsn:
                    formData.hsn.trim(),

                  costPrice:
                    formData.costPrice,
                }),
            }
          );


        const responseText =
          await response.text();


        if (!response.ok) {

          let message =
            "Unable to add medicine.";


          try {

            const data =
              JSON.parse(
                responseText
              );


            message =
              data.error ||
              data.message ||
              message;

          } catch {

            if (
              responseText.trim()
            ) {

              message =
                responseText.trim();
            }
          }


          if (
            response.status ===
            409
          ) {

            message =
              "Medicine ID already exists. Please use a different Medicine ID.";
          }


          throw new Error(
            message
          );
        }


        setSuccess(
          "Medicine added successfully!"
        );


        setFormData({

          id: "",

          name: "",

          batchNumber: "",

          category: "",

          quantity: "",

          price: "",

          manufacturer: "",

          disease: "",

          mfgDate: "",

          expiryDate: "",

          gstRate: "5",

          hsn: "3004",
          costPrice: "",
        });


        setTimeout(
          () => {
            onSuccess();
          },
          700
        );


      } catch (err) {

        console.error(
          "Add medicine error:",
          err
        );


        setError(
          err.message ||
          "Unable to add medicine. Make sure the Java API server is running."
        );


      } finally {

        setLoading(
          false
        );
      }
    };


  return (

    <div
      style={{
        maxWidth:
          "1000px",

        margin:
          "0 auto",

        overflowY:
          "auto",

        maxHeight:
          "calc(100vh - 120px)",
      }}
    >

      <div
        className="dashboard-card"
        style={{
          padding:
            "28px",
        }}
      >

        <div
          style={{
            marginBottom:
              "25px",
          }}
        >

          <h2
            style={{
              margin:
                "0 0 8px 0",

              fontSize:
                "24px",
            }}
          >
            Add New Medicine
          </h2>


          <p
            style={{
              margin: 0,

              color:
                "#6b7280",
            }}
          >
            Add a medicine manually or upload multiple medicines using a CSV file.
          </p>

        </div>


        {/* ================= ERROR ================= */}

        {error && (

          <div
            style={{
              marginBottom:
                "20px",

              padding:
                "13px 16px",

              borderRadius:
                "8px",

              background:
                "#fef2f2",

              color:
                "#b91c1c",

              border:
                "1px solid #fecaca",

              fontSize:
                "14px",
            }}
          >
            {error}
          </div>

        )}


        {/* ================= SUCCESS ================= */}

        {success && (

          <div
            style={{
              marginBottom:
                "20px",

              padding:
                "13px 16px",

              borderRadius:
                "8px",

              background:
                "#ecfdf5",

              color:
                "#047857",

              border:
                "1px solid #a7f3d0",

              fontSize:
                "14px",
            }}
          >
            {success}
          </div>

        )}


        {/* =================================================
           BULK CSV SECTION
        ================================================= */}

        <div
          style={{
            marginBottom:
              "28px",

            padding:
              "18px",

            borderRadius:
              "12px",

            border:
              "1px solid #a7f3d0",

            background:
              "#f0fdf4",
          }}
        >

          <div
            style={{
              display:
                "flex",

              justifyContent:
                "space-between",

              alignItems:
                "center",

              gap:
                "15px",

              flexWrap:
                "wrap",
            }}
          >

            <div>

              <h3
                style={{
                  margin:
                    "0 0 5px",

                  fontSize:
                    "17px",

                  color:
                    "#065f46",
                }}
              >
                Bulk Upload Medicines
              </h3>


              <p
                style={{
                  margin:
                    0,

                  color:
                    "#4b5563",

                  fontSize:
                    "13px",

                  lineHeight:
                    1.5,
                }}
              >
                Import multiple medicines from a CSV file. Existing Medicine IDs will be rejected.
              </p>

            </div>


            <div
              style={{
                display:
                  "flex",

                gap:
                  "10px",

                flexWrap:
                  "wrap",
              }}
            >

              <button
                type="button"
                onClick={
                  handleDownloadTemplate
                }
                disabled={
                  loading ||
                  bulkLoading
                }
                style={{
                  padding:
                    "10px 15px",

                  borderRadius:
                    "8px",

                  border:
                    "1px solid #059669",

                  background:
                    "#ffffff",

                  color:
                    "#047857",

                  cursor:
                    loading ||
                    bulkLoading
                      ? "not-allowed"
                      : "pointer",

                  fontWeight:
                    "600",

                  fontSize:
                    "13px",
                }}
              >
                ↓ CSV Template
              </button>


              <button
                type="button"
                onClick={
                  handleBulkUpload
                }
                disabled={
                  loading ||
                  bulkLoading
                }
                style={{
                  padding:
                    "10px 16px",

                  borderRadius:
                    "8px",

                  border:
                    "none",

                  background:
                    loading ||
                    bulkLoading
                      ? "#6ee7b7"
                      : "#059669",

                  color:
                    "#ffffff",

                  cursor:
                    loading ||
                    bulkLoading
                      ? "not-allowed"
                      : "pointer",

                  fontWeight:
                    "600",

                  fontSize:
                    "13px",
                }}
              >
                {bulkLoading
                  ? "Importing CSV..."
                  : "↑ Bulk Upload CSV"}
              </button>

            </div>

          </div>


          <div
            style={{
              marginTop:
                "12px",

              fontSize:
                "12px",

              color:
                "#6b7280",
            }}
          >
            Required columns: id, name, batchNumber, category, quantity, price, manufacturer, disease, mfgDate, expiryDate. Optional (add after expiryDate): gstRate (0, 5, 12, 18 or 28) and hsn. Medicines without them get 5% / 3004.
          </div>

        </div>


        {/* =================================================
           BULK RESULT
        ================================================= */}

        {bulkResult && (

          <div
            style={{
              marginBottom:
                "25px",

              padding:
                "18px",

              borderRadius:
                "10px",

              border:
                "1px solid #e5e7eb",

              background:
                "#fafafa",
            }}
          >

            <h3
              style={{
                margin:
                  "0 0 12px",

                fontSize:
                  "17px",

                color:
                  "#111827",
              }}
            >
              Bulk Upload Result
            </h3>


            <div
              style={{
                display:
                  "grid",

                gridTemplateColumns:
                  "repeat(4, minmax(0, 1fr))",

                gap:
                  "10px",

                marginBottom:
                  "15px",
              }}
            >

              <BulkResultBox
                label="CSV Rows"
                value={
                  bulkResult.totalRows
                }
              />


              <BulkResultBox
                label="Valid Rows"
                value={
                  bulkResult.validRows
                }
              />


              <BulkResultBox
                label="Imported"
                value={
                  bulkResult.imported
                }
                success
              />


              <BulkResultBox
                label="Failed"
                value={
                  bulkResult.failed
                }
                danger={
                  bulkResult.failed >
                  0
                }
              />

            </div>


            {bulkResult.errors.length >
              0 && (

              <div>

                <div
                  style={{
                    fontWeight:
                      "600",

                    color:
                      "#991b1b",

                    marginBottom:
                      "7px",

                    fontSize:
                      "13px",
                  }}
                >
                  Failed Rows
                </div>


                <div
                  style={{
                    maxHeight:
                      "180px",

                    overflowY:
                      "auto",

                    background:
                      "#fff7f7",

                    border:
                      "1px solid #fecaca",

                    borderRadius:
                      "8px",

                    padding:
                      "10px 12px",
                  }}
                >

                  {bulkResult.errors.map(
                    (
                      message,
                      index
                    ) => (

                      <div
                        key={`${index}-${message}`}
                        style={{
                          color:
                            "#991b1b",

                          fontSize:
                            "12px",

                          padding:
                            "3px 0",
                        }}
                      >
                        {message}
                      </div>

                    )
                  )}

                </div>

              </div>

            )}

          </div>

        )}


        {/* =================================================
           MANUAL ADD FORM
        ================================================= */}

        <form
          onSubmit={
            handleSubmit
          }
        >

          <div
            style={{
              display:
                "grid",

              gridTemplateColumns:
                "repeat(2, minmax(0, 1fr))",

              gap:
                "20px",
            }}
          >

            <FormField
              label="Medicine ID"
              name="id"
              value={
                formData.id
              }
              onChange={
                handleChange
              }
              placeholder="Example: MED001"
              required
            />


            <FormField
              label="Medicine Name"
              name="name"
              value={
                formData.name
              }
              onChange={
                handleChange
              }
              placeholder="Example: Paracetamol"
              required
            />


            <FormField
              label="Batch Number"
              name="batchNumber"
              value={
                formData.batchNumber
              }
              onChange={
                handleChange
              }
              placeholder="Example: BATCH2026A"
              required
            />


            <FormField
              label="Category"
              name="category"
              value={
                formData.category
              }
              onChange={
                handleChange
              }
              placeholder="Example: Tablet"
              required
            />


            <FormField
              label="Quantity"
              name="quantity"
              type="number"
              min="0"
              step="1"
              value={
                formData.quantity
              }
              onChange={
                handleChange
              }
              placeholder="Example: 100"
              required
            />


            <FormField
              label="Price"
              name="price"
              type="number"
              min="0"
              step="0.01"
              value={
                formData.price
              }
              onChange={
                handleChange
              }
              placeholder="Example: 25.50"
              required
            />


            <FormField
              label="Manufacturer"
              name="manufacturer"
              value={
                formData.manufacturer
              }
              onChange={
                handleChange
              }
              placeholder="Example: ABC Pharma"
              required
            />


            <FormField
              label="Disease"
              name="disease"
              value={
                formData.disease
              }
              onChange={
                handleChange
              }
              placeholder="Example: Fever"
              required
            />


            <FormField
              label="Manufacturing Date"
              name="mfgDate"
              type="date"
              value={
                formData.mfgDate
              }
              onChange={
                handleChange
              }
              required
            />


            <FormField
              label="Expiry Date"
              name="expiryDate"
              type="date"
              value={
                formData.expiryDate
              }
              onChange={
                handleChange
              }
              required
            />


            <GstFields
              gstRate={
                formData.gstRate
              }
              hsn={
                formData.hsn
              }
              costPrice={
                formData.costPrice
              }
              onChange={
                handleChange
              }
            />

          </div>


          <div
            style={{
              display:
                "flex",

              justifyContent:
                "flex-end",

              gap:
                "12px",

              marginTop:
                "30px",

              paddingTop:
                "22px",

              borderTop:
                "1px solid #e5e7eb",
            }}
          >

            <button
              type="button"
              onClick={
                onCancel
              }
              disabled={
                loading ||
                bulkLoading
              }
              style={{
                padding:
                  "11px 22px",

                borderRadius:
                  "8px",

                border:
                  "1px solid #d1d5db",

                background:
                  "#ffffff",

                color:
                  "#374151",

                cursor:
                  loading ||
                  bulkLoading
                    ? "not-allowed"
                    : "pointer",

                fontWeight:
                  "600",
              }}
            >
              Cancel
            </button>


            <button
              type="submit"
              disabled={
                loading ||
                bulkLoading
              }
              style={{
                padding:
                  "11px 24px",

                borderRadius:
                  "8px",

                border:
                  "none",

                background:
                  loading ||
                  bulkLoading
                    ? "#6ee7b7"
                    : "#059669",

                color:
                  "#ffffff",

                cursor:
                  loading ||
                  bulkLoading
                    ? "not-allowed"
                    : "pointer",

                fontWeight:
                  "600",
              }}
            >
              {loading
                ? "Adding Medicine..."
                : "Add Medicine"}
            </button>

          </div>

        </form>

      </div>

    </div>
  );
}


/* =========================================================
   BULK RESULT BOX
========================================================= */

export default AddMedicine;
