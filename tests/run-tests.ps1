# Runs MedTrack's automatic tests against a throwaway server and database.
# Your real database and your running server are never touched.
#
#   .\tests\run-tests.ps1            (takes about a minute)
#   .\tests\run-tests.ps1 -Port 8123 (if 8099 is already in use)
#
# Needs Java (JDK) on the PATH. Prints PASS/FAIL for every check and exits
# with code 0 only when everything passed.

param([int]$Port = 8099)

$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot -Parent
$work = Join-Path $env:TEMP ("medtrack_test_" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$classes = Join-Path $work "classes"
$dbFile = Join-Path $work "data\test.db"
$cp = "$classes;$root\lib\*"
$base = "http://localhost:$Port/api"
$script:pass = 0
$script:fail = 0
$script:serverLog = Join-Path $work "server.log"
$script:paidStart = "2020-01-01"   # charging already on, so the plan rules can be tested
$script:earlierLog = @()

New-Item -ItemType Directory -Force $classes, (Split-Path $dbFile) | Out-Null

function Stop-TestServer {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like "*$work*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
    # Each start overwrites the log, so keep what this run wrote.
    if (Test-Path $script:serverLog) { $script:earlierLog += @(Get-Content $script:serverLog) }
}

function Start-TestServer {
    $env:PORT = "$Port"
    $env:DB_PATH = $dbFile
    $env:BACKUP_ENABLED = "false"
    $env:ADMIN_EMAILS = "platform@test.com,boss@test.com"
    $env:PAID_START = $script:paidStart
    Start-Process java -ArgumentList "--enable-native-access=ALL-UNNAMED", "-cp", "`"$cp`"", "ApiServer" `
        -WorkingDirectory $work -WindowStyle Hidden `
        -RedirectStandardError $script:serverLog -RedirectStandardOutput (Join-Path $work "server.out") | Out-Null
    Remove-Item Env:PORT, Env:DB_PATH, Env:BACKUP_ENABLED, Env:ADMIN_EMAILS, Env:PAID_START -ErrorAction SilentlyContinue

    for ($i = 0; $i -lt 40; $i++) {
        try {
            if ((Invoke-WebRequest "$base/health" -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { return }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    throw "Test server did not start. See $script:serverLog"
}

function T($name, $ok) {
    if ($ok) { $script:pass++ } else { $script:fail++ }
    "{0,-80} {1}" -f $name, $(if ($ok) { "PASS" } else { "FAIL" })
}

function Call($method, $path, $body = $null, $token = $null, $headers = @{}) {
    $h = @{} + $headers
    if ($token) { $h["Authorization"] = "Bearer $token" }
    $json = $null
    if ($body -is [string]) { $json = $body } elseif ($body -ne $null) { $json = ($body | ConvertTo-Json -Depth 8) }
    try {
        $r = Invoke-WebRequest "$base$path" -Method $method -Headers $h -Body $json -ContentType "application/json" -UseBasicParsing
        return [pscustomobject]@{ code = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json -ErrorAction SilentlyContinue); headers = $r.Headers }
    } catch {
        $resp = $_.Exception.Response
        $txt = ""
        if ($resp) { $txt = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() }
        return [pscustomobject]@{ code = [int]$resp.StatusCode; body = ($txt | ConvertFrom-Json -ErrorAction SilentlyContinue); headers = $resp.Headers }
    }
}

function Med($id, $name, $qty, $price, $gst) {
    @{ id = $id; name = $name; batchNumber = "B-$id"; category = "Tablet"; quantity = "$qty"; price = "$price";
       manufacturer = "Cipla"; disease = "General"; mfgDate = "2026-01-01"; expiryDate = "2028-01-01"; gstRate = "$gst"; hsn = "3004" }
}

function Stock($token) {
    $s = @{}
    (Call GET "/medicines" $null $token).body | ForEach-Object { $s[$_.id] = $_.quantity }
    $s
}

try {
    "== Compiling =="
    $sources = Get-ChildItem "$root\src\*.java" | ForEach-Object { $_.FullName }
    $testSources = Get-ChildItem "$PSScriptRoot\java\*.java" | ForEach-Object { $_.FullName }
    javac --release 21 -encoding UTF-8 -cp "$root\lib\*" -d $classes @sources @testSources 2>&1 | Where-Object { $_ -notmatch "^Note:" }
    if ($LASTEXITCODE -ne 0) { throw "Compile failed" }

    "== GST maths =="
    java --enable-native-access=ALL-UNNAMED -cp $cp GstMathTest 2>&1 | Where-Object { $_ -notmatch "^WARNING" }
    T "GST maths (hand-worked cases + 200,000 random lines)" ($LASTEXITCODE -eq 0)

    java --enable-native-access=ALL-UNNAMED -cp $cp GoogleAuthTest 2>&1 | Where-Object { $_ -notmatch "^WARNING" }
    T "Google tokens: genuine one passes; forged, expired, tampered and 'alg none' are refused" ($LASTEXITCODE -eq 0)

    java --enable-native-access=ALL-UNNAMED -cp $cp PlansTest 2>&1 | Where-Object { $_ -notmatch "^WARNING" }
    T "plan rules: free launch, announced date, trial, grace, ended, paid, suspended" ($LASTEXITCODE -eq 0)

    "== Preparing database and starting the server =="
    $env:DB_PATH = $dbFile
    java --enable-native-access=ALL-UNNAMED -cp $cp Seed 2>&1 | Where-Object { $_ -notmatch "^WARNING" } | Out-Null
    Remove-Item Env:DB_PATH
    Start-TestServer

    "== Sign-in, passwords, sessions =="
    $r = Call POST "/auth/login" @{ username = "legacy_u"; password = "WrongPass99" }; T "wrong password rejected (401)" ($r.code -eq 401)
    $r = Call POST "/auth/login" @{ username = "legacy_u"; password = "LegacyPass1" }; T "old SHA-256 account can still sign in" ($r.code -eq 200 -and $r.body.token)
    $r = Call POST "/auth/login" @{ username = "nobody"; password = "whatever123" }; T "unknown user rejected (401)" ($r.code -eq 401)
    $other = (Call POST "/auth/login" @{ username = "legacy_u"; password = "LegacyPass1" }).body.token
    $r = Call POST "/auth/login" @{ username = "own_a@test.com"; password = "OwnerPass1" }; T "sign in by email, role is owner" ($r.code -eq 200 -and $r.body.role -eq "owner")
    $own = $r.body.token
    $r = Call GET "/medicines"; T "no token -> 401" ($r.code -eq 401)
    $r = Call GET "/health"; T "health check" ($r.code -eq 200 -and $r.body.status -eq "ok")

    Stop-TestServer; Start-TestServer
    $r = Call GET "/medicines" $null $own; T "same session still valid after a server restart" ($r.code -eq 200)
    $r = Call POST "/auth/forgot-password" @{ email = "ghost@nowhere.com" }; $r2 = Call POST "/auth/forgot-password" @{ email = "own_a@test.com" }
    T "forgot-password answers the same for known and unknown emails" ($r.code -eq 200 -and $r2.code -eq 200)

    "== Team and roles =="
    $r = Call POST "/team" @{ username = "cashier1"; email = "c1@test.com"; password = "CashierPass1"; role = "cashier" } $own; T "owner adds a cashier" ($r.code -eq 200)
    $cash = (Call POST "/auth/login" @{ username = "cashier1"; password = "CashierPass1" }).body.token
    $r = Call POST "/medicines" (Med "NOPE" "Nope" 1 1 5) $cash; T "cashier cannot add medicines (403)" ($r.code -eq 403)
    $r = Call GET "/team" $null $cash; T "cashier cannot open the team list (403)" ($r.code -eq 403)
    $r = Call PUT "/shop" @{ name = "Hacked" } $cash; T "cashier cannot edit shop details (403)" ($r.code -eq 403)

    "== Shop details + medicines with GST =="
    $r = Call PUT "/shop" @{ name = "Sharma Medical Store"; address = "12 Market Road, Pune"; phone = "+91 98765 43210"; gstin = "27ABCDE1234F1Z5"; drugLicense = "MH-PUN-12345"; state = "Maharashtra"; invoicePrefix = "TST-"; pricesIncludeGst = $true } $own
    T "owner saves shop details" ($r.code -eq 200 -and $r.body.gstin -eq "27ABCDE1234F1Z5")
    $r = Call PUT "/shop" @{ name = "X"; gstin = "BADGSTIN" } $own; T "bad GSTIN rejected (400)" ($r.code -eq 400)
    Call POST "/medicines" (Med "A1" "Paracetamol 500" 10 105 5) $own | Out-Null
    Call POST "/medicines" (Med "B1" "Amoxicillin" 10 112 12) $own | Out-Null
    $r = Call POST "/medicines" (Med "C1" "Vitamin Tonic" 10 59 18) $own; T "medicines saved with their GST rate" ($r.code -eq 201)
    $r = Call POST "/medicines" (Med "D1" "Bad Rate" 10 20 7) $own; T "GST rate 7% rejected (400)" ($r.code -eq 400)
    $r = Call GET "/medicines/C1" $null $own; T "GST rate and HSN come back from the API" ($r.body.gstRate -eq 18 -and $r.body.hsn -eq "3004")

    "== Multi-item GST bill =="
    $bill = @{ customerName = "Ravi Kumar"; customerPhone = "9876500000"; paymentMode = "upi"; items = @(
        @{ medicineId = "A1"; quantity = 2; unitPrice = 1 },
        @{ medicineId = "B1"; quantity = 1 },
        @{ medicineId = "C1"; quantity = 3; discountPercent = 10 }) }
    $r = Call POST "/invoices" $bill $own; $inv = $r.body.invoice
    T "3-item bill created" ($r.code -eq 201 -and $inv.items.Count -eq 3)
    T "invoice number uses the shop prefix (TST-00001)" ($inv.invoiceNo -eq "TST-00001")
    T "taxable 435.00, CGST 23.15, SGST 23.15, discount 17.70" ($inv.totals.taxable -eq 435 -and $inv.totals.cgst -eq 23.15 -and $inv.totals.sgst -eq 23.15 -and $inv.totals.discount -eq 17.7)
    T "round-off -0.30, payable 481.00" ($inv.totals.roundOff -eq -0.3 -and $inv.totals.total -eq 481)
    T "price sent by the browser is ignored (MRP 105 used)" ($inv.items[0].unitPrice -eq 105)
    $s = Stock $own; T "stock reduced for all three medicines" ($s["A1"] -eq 8 -and $s["B1"] -eq 9 -and $s["C1"] -eq 7)
    T "every bill line also reaches the sales table (reports)" (@((Call GET "/sales" $null $own).body).Count -eq 3)

    "== Bills are all-or-nothing =="
    $r = Call POST "/invoices" @{ items = @(@{ medicineId = "A1"; quantity = 1 }, @{ medicineId = "B1"; quantity = 500 }) } $own; T "one item over stock refuses the whole bill (409)" ($r.code -eq 409)
    $s = Stock $own; T "...and the other item's stock is untouched" ($s["A1"] -eq 8)
    $r = Call POST "/invoices" @{ items = @(@{ medicineId = "EXP1"; quantity = 1 }) } $other; T "expired medicine cannot be sold (409)" ($r.code -eq 409)
    $r = Call POST "/invoices" @{ items = @(@{ medicineId = "NOPE"; quantity = 1 }) } $own; T "unknown medicine (404)" ($r.code -eq 404)
    $r = Call POST "/invoices" @{ items = @(@{ medicineId = "A1"; quantity = 1.5 }) } $own; T "fractional quantity rejected (400)" ($r.code -eq 400)
    $r = Call POST "/invoices" @{ items = @(@{ medicineId = "A1"; quantity = 1; discountPercent = 150 }) } $own; T "discount over 100% rejected (400)" ($r.code -eq 400)
    $r = Call POST "/invoices" @{ items = @() } $own; T "empty bill rejected (400)" ($r.code -eq 400)
    $r = Call POST "/invoices" @{ items = @(@{ medicineId = "A1"; quantity = 1 }) } $cash; T "cashier can create a bill, numbering has no gap (TST-00002)" ($r.code -eq 201 -and $r.body.invoice.invoiceNo -eq "TST-00002")

    "== Shop isolation =="
    $r = Call GET "/invoices/$($inv.id)" $null $other; T "another shop cannot open this invoice (404)" ($r.code -eq 404)
    $r = Call GET "/invoices" $null $other; T "another shop sees no invoices" (@($r.body).Count -eq 0)
    $r = Call POST "/invoices/$($inv.id)/cancel" $null $other; T "another shop cannot cancel it (404)" ($r.code -eq 404)

    "== Returns and credit notes =="
    $iA = ($inv.items | Where-Object { $_.medicineId -eq "A1" }).itemId
    $iB = ($inv.items | Where-Object { $_.medicineId -eq "B1" }).itemId
    $iC = ($inv.items | Where-Object { $_.medicineId -eq "C1" }).itemId
    $r = Call POST "/invoices/$($inv.id)/return" @{ reason = "Wrong medicine"; items = @(@{ itemId = $iA; quantity = 1 }) } $own; $cn1 = $r.body.creditNote
    T "return 1 Paracetamol: credit note CN-00001, refund 105.00" ($r.code -eq 201 -and $cn1.noteNo -eq "CN-00001" -and $cn1.totals.total -eq 105)
    # Paracetamol: 10 - 2 (invoice 1) - 1 (the cashier's bill) = 7, then +1 returned = 8.
    $s = Stock $own; T "returned medicine is back in stock (7 -> 8)" ($s["A1"] -eq 8)
    $r = Call POST "/invoices/$($inv.id)/return" @{ items = @(@{ itemId = $iC; quantity = 1 }) } $own; $cn2 = $r.body.creditNote
    T "return 1 of 3 discounted Vitamin Tonic: refund 53.00 (GST 4.05 + 4.05)" ($cn2.totals.total -eq 53 -and $cn2.totals.cgst -eq 4.05)
    $r = Call POST "/invoices/$($inv.id)/return" @{ items = @(@{ itemId = $iC; quantity = 2 }) } $own; $cn3 = $r.body.creditNote
    T "return the last 2: gets exactly what is left (106.00), pieces add to 135 taxable" ($cn3.totals.total -eq 106 -and ($cn2.totals.taxable + $cn3.totals.taxable) -eq 135)
    $r = Call POST "/invoices/$($inv.id)/return" @{ items = @(@{ itemId = $iA; quantity = 2 }) } $own; T "returning more than is left is refused (409)" ($r.code -eq 409)
    $before = Stock $own
    $r = Call POST "/invoices/$($inv.id)/return" @{ items = @(@{ itemId = $iB; quantity = 1 }, @{ itemId = $iA; quantity = 5 }) } $own
    $after = Stock $own; T "a return with one bad line changes nothing" ($r.code -eq 409 -and $after["B1"] -eq $before["B1"])
    $r = Call POST "/invoices/$($inv.id)/return" @{ items = @(@{ itemId = $iA; quantity = 1 }, @{ itemId = $iB; quantity = 1 }) } $own; $cn4 = $r.body.creditNote
    T "last items returned in one note (CN-00004, no numbering gap)" ($r.code -eq 201 -and $cn4.noteNo -eq "CN-00004" -and $cn4.totals.total -eq 217)
    $full = (Call GET "/invoices/$($inv.id)" $null $own).body.invoice
    T "total refunded = the invoice total (481.00), never more" ($full.returnedTotal -eq 481)
    $s = Stock $own; T "invoice 1 fully back in stock (Paracetamol 9 because the cashier's bill holds 1)" ($s["A1"] -eq 9 -and $s["B1"] -eq 10 -and $s["C1"] -eq 10)
    $r = Call POST "/invoices/$($inv.id)/return" @{ items = @(@{ itemId = $iB; quantity = 1 }) } $own; T "fully returned invoice takes no more returns (409)" ($r.code -eq 409)
    # Sales so far: invoice 1 (+481.30), the cashier's bill (+105.00) and all the returns (-481.30).
    $net = [math]::Round((@((Call GET "/sales" $null $own).body) | Measure-Object -Property totalAmount -Sum).Sum, 2)
    T "revenue after returns is exactly the one bill that was not returned (105.00)" ($net -eq 105)
    $r = Call POST "/invoices/$($inv.id)/cancel" $null $own; T "an invoice with returns cannot be cancelled (409)" ($r.code -eq 409)
    $inv2 = (Call POST "/invoices" @{ items = @(@{ medicineId = "A1"; quantity = 3 }) } $own).body.invoice
    $r = Call POST "/invoices/$($inv2.id)/return" @{ restock = $false; reason = "Damaged"; items = @(@{ itemId = $inv2.items[0].itemId; quantity = 1 }) } $own
    # 9 in stock, the bill takes 3 -> 6; returning 1 with restock OFF must leave it at 6.
    $s = Stock $own; T "damaged return with restock OFF does not add stock" ($r.code -eq 201 -and $s["A1"] -eq 6)
    $r = Call POST "/invoices/$($inv2.id)/return" @{ items = @(@{ itemId = $inv2.items[0].itemId; quantity = 1 }) } $cash; T "cashier cannot make a return (403)" ($r.code -eq 403)
    $r = Call POST "/invoices/$($inv2.id)/return" @{ items = @(@{ itemId = $inv2.items[0].itemId; quantity = 1 }) } $other; T "another shop cannot return against it (404)" ($r.code -eq 404)
    $r = Call GET "/credit-notes/$($cn1.id)" $null $other; T "another shop cannot open a credit note (404)" ($r.code -eq 404)
    $r = Call GET "/credit-notes?search=CN-00002" $null $own; T "credit notes can be searched by number" (@($r.body).Count -eq 1)

    "== Cancel a bill =="
    $inv3 = (Call POST "/invoices" @{ items = @(@{ medicineId = "B1"; quantity = 2 }) } $own).body.invoice
    $r = Call POST "/invoices/$($inv3.id)/cancel" $null $cash; T "cashier cannot cancel (403)" ($r.code -eq 403)
    $r = Call POST "/invoices/$($inv3.id)/cancel" $null $own; T "owner cancels, stock restored" ($r.code -eq 200 -and (Stock $own)["B1"] -eq 10)
    $r = Call POST "/invoices/$($inv3.id)/cancel" $null $own; T "cancelling twice refused (409)" ($r.code -eq 409)

    "== Reports and exports =="
    # State by now: invoices 481 (fully returned) + 105 + 315 = 901 sold; one cancelled bill (224) must not count;
    # 5 credit notes = 105 + 53 + 106 + 217 + 105 = 586 refunded; so net sales = 315.
    $sum = (Call GET "/reports/summary" $null $own).body
    T "sales total 901.00 over 3 bills (the cancelled bill is left out)" ($sum.sales.total -eq 901 -and $sum.sales.count -eq 3)
    T "returns total 586.00 over 5 credit notes" ($sum.returns.total -eq 586 -and $sum.returns.count -eq 5)
    T "net sales 315.00 (taxable 300, CGST 7.50, SGST 7.50)" ($sum.net.total -eq 315 -and $sum.net.taxable -eq 300 -and $sum.net.cgst -eq 7.5 -and $sum.net.sgst -eq 7.5)
    $r5 = @($sum.byRate | Where-Object { $_.rate -eq 5 })[0]; $r12 = @($sum.byRate | Where-Object { $_.rate -eq 12 })[0]; $r18 = @($sum.byRate | Where-Object { $_.rate -eq 18 })[0]
    T "net GST by rate: 5% = 300.00 taxable; 12% and 18% fully returned = 0" ($r5.taxable -eq 300 -and $r5.total -eq 315 -and $r12.taxable -eq 0 -and $r18.taxable -eq 0)
    $h5 = @($sum.hsn | Where-Object { $_.hsn -eq "3004" -and $_.rate -eq 5 })[0]
    T "HSN 3004 at 5%: net quantity 3, taxable 300.00" ($h5.quantity -eq 3 -and $h5.taxable -eq 300)
    T "top medicine: Paracetamol, 3 units net, 315.00" ($sum.top[0].medicineId -eq "A1" -and $sum.top[0].units -eq 3 -and $sum.top[0].revenue -eq 315)
    $cashRow = @($sum.paymentModes | Where-Object { $_.mode -eq "cash" })[0]; $upiRow = @($sum.paymentModes | Where-Object { $_.mode -eq "upi" })[0]
    T "payment modes: cash sales 420.00, UPI sales 481.00" ($cashRow.sales -eq 420 -and $upiRow.sales -eq 481)
    T "day by day: today net 315.00 over 3 bills" (@($sum.daily).Count -eq 1 -and $sum.daily[0].net -eq 315 -and $sum.daily[0].bills -eq 3)
    $yesterday = (Get-Date).AddDays(-1).ToString("yyyy-MM-dd")
    $hundredAgo = (Get-Date).AddDays(-100).ToString("yyyy-MM-dd")
    $r = Call GET "/reports/summary?from=$hundredAgo&to=$yesterday" $null $own; T "a period before today shows no sales" ($r.code -eq 200 -and $r.body.sales.total -eq 0 -and $r.body.net.total -eq 0)
    $r = Call GET "/reports/summary?from=2015-01-01&to=2026-01-01" $null $own; T "a period longer than 5 years is refused (400)" ($r.code -eq 400)
    $r = Call GET "/reports/summary?from=2026-12-31&to=2026-01-01" $null $own; T "start date after end date rejected (400)" ($r.code -eq 400)
    $r = Call GET "/reports/summary?from=abc&to=2026-01-01" $null $own; T "badly written date rejected (400)" ($r.code -eq 400)
    $r = Call GET "/reports/summary" $null $cash; T "cashier cannot open reports (403)" ($r.code -eq 403)
    $r = Call GET "/reports/summary" $null $other; T "another shop's report is empty (no leak)" ($r.body.sales.total -eq 0)
    $r = Call GET "/reports/summary"; T "reports need a login (401)" ($r.code -eq 401)

    $st = (Call GET "/reports/stock?days=90&low=10" $null $own).body
    T "stock: 3 medicines, 26 units, worth 2340.00 at MRP" ($st.medicines -eq 3 -and $st.units -eq 26 -and $st.value -eq 2340)
    T "nothing expired, nothing expiring in 90 days" (@($st.expired).Count -eq 0 -and @($st.expiring).Count -eq 0)
    T "all 3 medicines have 10 or fewer units" (@($st.low).Count -eq 3)
    $st2 = (Call GET "/reports/stock?days=3650" $null $own).body; T "with a 10 year window all 3 count as expiring" (@($st2.expiring).Count -eq 3)

    function GetRaw($path, $token) {
        try { $x = Invoke-WebRequest "$base$path" -Headers @{ Authorization = "Bearer $token" } -UseBasicParsing
              return [pscustomobject]@{ code = [int]$x.StatusCode; text = $x.Content; type = $x.Headers["Content-Type"]; disp = $x.Headers["Content-Disposition"] } }
        catch { return [pscustomobject]@{ code = [int]$_.Exception.Response.StatusCode; text = ""; type = ""; disp = "" } }
    }
    function Lines($raw) { @($raw.text.TrimEnd() -split "`r?`n") }
    $x = GetRaw "/reports/export?type=register" $own
    T "register CSV: right type, file name, Excel BOM" ($x.code -eq 200 -and $x.type -like "text/csv*" -and $x.disp -like "*gst-sales-register-*.csv*" -and $x.text.StartsWith([string][char]0xFEFF))
    T "register CSV: header + 4 invoices (1 cancelled) + 5 credit notes" ((Lines $x).Count -eq 10)
    T "register CSV: credit notes are negative, cancelled bill shows 0.00" ($x.text -match '"Credit note","CN-00001"' -and $x.text -match '"-105.00"' -and $x.text -match '"Cancelled"')
    $x = GetRaw "/reports/export?type=items" $own; T "item-wise CSV: header + 5 invoice lines + 6 credit lines" ((Lines $x).Count -eq 12)
    $x = GetRaw "/reports/export?type=hsn" $own; T "HSN summary CSV: header + 3 rows" ((Lines $x).Count -eq 4)
    $x = GetRaw "/reports/export?type=rate" $own; T "rate-wise CSV: header + 3 rows" ((Lines $x).Count -eq 4)
    $x = GetRaw "/reports/export?type=inventory" $own; T "inventory CSV: header + 3 medicines" ((Lines $x).Count -eq 4)
    $x = GetRaw "/reports/export?type=expiry&days=90" $own; T "expiry CSV: header only when nothing is expiring" ((Lines $x).Count -eq 1)
    $x = GetRaw "/reports/export?type=nonsense" $own; T "unknown export type rejected (400)" ($x.code -eq 400)
    $x = GetRaw "/reports/export?type=register" $cash; T "cashier cannot export (403)" ($x.code -eq 403)
    Call POST "/medicines" (Med "INJ1" "=1+1+cmd|' /C calc'!A0" 1 1 5) $own | Out-Null
    $x = GetRaw "/reports/export?type=inventory" $own; T "spreadsheet formulas in names are defused (leading apostrophe)" ($x.text -match '"''=1\+1\+cmd')
    Call DELETE "/medicines/INJ1" $null $own | Out-Null

    "== Many people at the same time =="
    $token = $own
    $billOut = @(java --enable-native-access=ALL-UNNAMED -cp $cp BillLoad "http://localhost:$Port" $token 2>&1 | Where-Object { $_ -notmatch "^WARNING" })
    $billOut | ForEach-Object { "   $_" }
    $returnOut = @(java --enable-native-access=ALL-UNNAMED -cp $cp ReturnLoad "http://localhost:$Port" $token 2>&1 | Where-Object { $_ -notmatch "^WARNING" })
    $returnOut | ForEach-Object { "   $_" }
    T "30 parallel bills for 10 units: exactly 10 sold, no errors" (($billOut -join "`n") -match "created=10 refused\(out of stock\)=20 errors=0")
    T "20 parallel returns of 10 sold units: exactly 10 accepted, stock back to 10" ((($returnOut -join "`n") -match "accepted=10 refused=10 errors=0") -and (($returnOut -join "`n") -match "never more: true") -and (($returnOut -join "`n") -match "should be 10\): 10"))

    "== Customers and udhaar (credit) =="
    Call POST "/medicines" (Med "U1" "Udhaar Test Syrup" 50 100 5) $own | Out-Null
    $r = Call POST "/customers" @{ name = "Ramesh Traders"; phone = "98 111-22222"; creditLimit = 999999 } $cash
    $ramesh = $r.body.id
    T "cashier can add a customer (201); a cashier cannot set a credit limit" ($r.code -eq 201 -and $ramesh -gt 0 -and ((Call GET "/customers/$ramesh" $null $own).body.customer.creditLimit -eq 0))
    $r = Call POST "/customers" @{ name = "Same Phone"; phone = "9811122222" } $own; T "same phone number twice refused (409)" ($r.code -eq 409)
    $r = Call POST "/customers" @{ name = "Bad"; phone = "abc" } $own; T "bad phone rejected (400)" ($r.code -eq 400)
    $r = Call POST "/customers" @{ name = "" } $own; T "empty name rejected (400)" ($r.code -eq 400)
    $r = Call PUT "/customers/$ramesh" @{ name = "Ramesh Traders"; phone = "9811122222"; creditLimit = 500 } $own
    T "owner sets a credit limit of 500" ($r.code -eq 200 -and (Call GET "/customers/$ramesh" $null $own).body.customer.creditLimit -eq 500)
    $r = Call PUT "/customers/$ramesh" @{ name = "Ramesh Traders"; phone = "9811122222"; creditLimit = 90000 } $cash
    T "a cashier edit leaves the credit limit alone" ($r.code -eq 200 -and (Call GET "/customers/$ramesh" $null $own).body.customer.creditLimit -eq 500)

    $r = Call POST "/invoices" @{ paymentMode = "credit"; items = @(@{ medicineId = "U1"; quantity = 1 }) } $own; T "credit bill without a customer refused (400)" ($r.code -eq 400)
    $r = Call POST "/invoices" @{ paymentMode = "credit"; customerId = $ramesh; items = @(@{ medicineId = "U1"; quantity = 3 }) } $cash; $ci1 = $r.body.invoice
    T "credit bill of 300 for Ramesh (name comes from his record)" ($r.code -eq 201 -and $ci1.totals.total -eq 300 -and $ci1.customer.name -eq "Ramesh Traders" -and $ci1.balanceDue -eq 300)
    T "his balance is 300" ((Call GET "/customers/$ramesh" $null $own).body.customer.balance -eq 300)
    $r = Call POST "/invoices" @{ paymentMode = "credit"; customerId = $ramesh; amountPaid = 50; paidMode = "upi"; items = @(@{ medicineId = "U1"; quantity = 2 }) } $own; $ci2 = $r.body.invoice
    T "part-paid credit bill: 200 total, 50 paid, 150 due" ($r.code -eq 201 -and $ci2.amountPaid -eq 50 -and $ci2.balanceDue -eq 150)
    T "balance is now 450" ((Call GET "/customers/$ramesh" $null $own).body.customer.balance -eq 450)
    $r = Call POST "/invoices" @{ paymentMode = "credit"; customerId = $ramesh; items = @(@{ medicineId = "U1"; quantity = 1 }) } $own
    T "a bill over the credit limit is refused (409)" ($r.code -eq 409)
    T "...and the stock was not touched (50 - 3 - 2 = 45)" ((Stock $own)["U1"] -eq 45)
    $r = Call POST "/invoices" @{ paymentMode = "credit"; customerId = $ramesh; amountPaid = 999; items = @(@{ medicineId = "U1"; quantity = 1 }) } $own; T "amount paid above the bill total refused (400)" ($r.code -eq 400)
    $r = Call POST "/invoices" @{ paymentMode = "cash"; customerId = $ramesh; items = @(@{ medicineId = "U1"; quantity = 1 }) } $own
    T "a cash bill for a customer does not touch his udhaar" ($r.code -eq 201 -and (Call GET "/customers/$ramesh" $null $own).body.customer.balance -eq 450)

    $r = Call POST "/customers/$ramesh/payments" @{ amount = 0 } $cash; T "zero payment refused (400)" ($r.code -eq 400)
    $r = Call POST "/customers/$ramesh/payments" @{ amount = 10; mode = "credit" } $cash; T "payment mode 'credit' refused (400)" ($r.code -eq 400)
    $r = Call POST "/customers/$ramesh/payments" @{ amount = 200; mode = "upi" } $cash; T "cashier receives 200: balance 250" ($r.code -eq 201 -and $r.body.balance -eq 250)

    $r = Call POST "/invoices/$($ci1.id)/return" @{ refundMode = "cash"; items = @(@{ itemId = $ci1.items[0].itemId; quantity = 1 }) } $own
    T "returning 1 unit from a credit bill is forced to credit, not cash" ($r.code -eq 201 -and $r.body.creditNote.refundMode -eq "credit")
    T "...and lowers what he owes: 250 - 100 = 150" ((Call GET "/customers/$ramesh" $null $own).body.customer.balance -eq 150)
    $r = Call POST "/invoices/$($ci2.id)/cancel" $null $own
    T "cancelling the part-paid bill clears its 150 from his balance (now 0)" ($r.code -eq 200 -and (Call GET "/customers/$ramesh" $null $own).body.customer.balance -eq 0)

    $led = (Call GET "/customers/$ramesh" $null $own).body
    T "ledger lists sale, payment, return and cancel entries" (@($led.ledger | Where-Object { $_.kind -eq "sale" }).Count -eq 2 -and @($led.ledger | Where-Object { $_.kind -eq "payment" }).Count -eq 2 -and @($led.ledger | Where-Object { $_.kind -eq "return" }).Count -eq 1 -and @($led.ledger | Where-Object { $_.kind -eq "cancel" }).Count -eq 1)
    T "ledger entries add up to the balance (running total ends at 0)" ($led.ledger[0].balanceAfter -eq 0)

    $r = Call GET "/customers/$ramesh" $null $other; T "another shop cannot open this customer (404)" ($r.code -eq 404)
    $r = Call GET "/customers" $null $other; T "another shop sees no customers" (@($r.body.customers).Count -eq 0)
    $r = Call POST "/customers/$ramesh/payments" @{ amount = 10 } $other; T "another shop cannot record a payment (404)" ($r.code -eq 404)
    $r = Call POST "/invoices" @{ paymentMode = "credit"; customerId = $ramesh; items = @(@{ medicineId = "A1"; quantity = 1 }) } $other; T "another shop cannot bill someone else's customer (404)" ($r.code -eq 404 -or $r.code -eq 409)
    $r = Call GET "/customers"; T "customers need a login (401)" ($r.code -eq 401)

    $r = Call DELETE "/customers/$ramesh" $null $own; T "a customer with history cannot be deleted (409)" ($r.code -eq 409)
    $r = Call POST "/customers" @{ name = "Temp Person" } $own; $tmp = $r.body.id
    $r = Call DELETE "/customers/$tmp" $null $cash; T "cashier cannot delete a customer (403)" ($r.code -eq 403)
    $r = Call DELETE "/customers/$tmp" $null $own; T "owner deletes a customer with no history" ($r.code -eq 200)

    # Ten people paying the same customer at once: nothing may be lost.
    Call POST "/invoices" @{ paymentMode = "credit"; customerId = $ramesh; items = @(@{ medicineId = "U1"; quantity = 1 }) } $own | Out-Null
    $jobs = 1..10 | ForEach-Object {
        Start-Job -ArgumentList $base, $own, $ramesh -ScriptBlock {
            param($b, $tok, $id)
            try { (Invoke-WebRequest "$b/customers/$id/payments" -Method POST -Headers @{ Authorization = "Bearer $tok" } -Body '{"amount":10,"mode":"cash"}' -ContentType "application/json" -UseBasicParsing).StatusCode } catch { 0 }
        }
    }
    $codes = $jobs | Wait-Job | Receive-Job; $jobs | Remove-Job
    T "10 payments of 10 at the same moment: all accepted, balance exactly 0" (@($codes | Where-Object { $_ -eq 201 }).Count -eq 10 -and (Call GET "/customers/$ramesh" $null $own).body.customer.balance -eq 0)

    $r = Call POST "/customers" @{ name = "Sita Devi"; phone = "9000000001" } $own; $sita = $r.body.id
    Call POST "/invoices" @{ paymentMode = "credit"; customerId = $sita; items = @(@{ medicineId = "U1"; quantity = 1 }) } $cash | Out-Null
    $r = Call GET "/customers?dues=1" $null $own; T "dues list shows only Sita, total dues 100.00" (@($r.body.customers).Count -eq 1 -and $r.body.summary.totalDues -eq 100)
    $r = Call GET "/customers?search=ramesh" $null $cash; T "search finds Ramesh (cashier may look customers up)" (@($r.body.customers).Count -eq 1)
    $x = GetRaw "/reports/export?type=dues" $own; T "dues CSV: header + Sita" ($x.code -eq 200 -and (Lines $x).Count -eq 2 -and $x.text -match "Sita Devi")
    $x = GetRaw "/reports/export?type=dues" $cash; T "cashier cannot export the dues list (403)" ($x.code -eq 403)
    "== Suppliers and purchases =="
    $today = (Get-Date).ToString("yyyy-MM-dd")
    Call POST "/medicines" (Med "P1" "Purchase Test Tablet" 10 100 12) $own | Out-Null
    $r = Call GET "/suppliers" $null $cash; T "cashier cannot open suppliers (403)" ($r.code -eq 403)
    $r = Call POST "/suppliers" @{ name = "Alpha Pharma"; phone = "9111111111"; gstin = "27ABCDE1234F1Z5" } $own; $sup = $r.body.id
    T "owner adds a supplier" ($r.code -eq 201 -and $sup -gt 0)
    $r = Call POST "/suppliers" @{ name = "Same Phone"; phone = "91111 11111" } $own; T "same supplier phone twice refused (409)" ($r.code -eq 409)
    $r = Call POST "/suppliers" @{ name = "Bad"; gstin = "NOPE" } $own; T "bad supplier GSTIN rejected (400)" ($r.code -eq 400)
    $r = Call POST "/suppliers" "{broken" $own; T "broken JSON is a 400, not a crash" ($r.code -eq 400)
    $r = Call POST "/purchases" @{ supplierId = $sup; billDate = $today; items = @() } $own; T "purchase with no items refused (400)" ($r.code -eq 400)
    $r = Call POST "/purchases" @{ supplierId = $sup; billDate = "2099-01-01"; items = @(@{ name = "X"; batch = "B"; expiry = "2100-01-01"; mrp = 10; cost = 5; gstRate = 5; quantity = 1 }) } $own; T "future bill date refused (400)" ($r.code -eq 400)
    $r = Call POST "/purchases" @{ supplierId = $sup; billDate = $today; items = @(@{ name = "X"; batch = "B"; expiry = "2020-01-01"; mrp = 10; cost = 5; gstRate = 5; quantity = 1 }) } $own; T "already expired stock refused (400)" ($r.code -eq 400)
    $r = Call POST "/purchases" @{ supplierId = $sup; billDate = $today; items = @(@{ name = "X"; batch = "B"; expiry = "2029-01-01"; mrp = 10; cost = 5; gstRate = 7; quantity = 1 }) } $own; T "GST 7% on a purchase refused (400)" ($r.code -eq 400)
    $r = Call POST "/purchases" @{ supplierId = 99999; billDate = $today; items = @(@{ name = "X"; batch = "B"; expiry = "2029-01-01"; mrp = 10; cost = 5; gstRate = 5; quantity = 1 }) } $own; T "unknown supplier refused (404)" ($r.code -eq 404)
    $pBody = @{ supplierId = $sup; billNo = "A-100"; billDate = $today; amountPaid = 100; paidMode = "upi"; clientRef = "purchase-1"; items = @(
        @{ medicineId = "P1"; batch = "B-P1"; expiry = "2029-01-01"; mrp = 100; cost = 60; gstRate = 12; hsn = "3004"; quantity = 10; freeQty = 2 },
        @{ medicineId = "P1"; batch = "NEWB"; expiry = "2029-06-01"; mrp = 110; cost = 70; gstRate = 12; quantity = 5 },
        @{ name = "Brand New Syrup"; batch = "X1"; expiry = "2029-01-01"; mrp = 50; cost = 30; gstRate = 5; quantity = 10 }) }
    $r = Call POST "/purchases" $pBody $own; $pur = $r.body.purchase
    T "purchase saved: PUR-00001, taxable 1250, GST 129, total 1379" ($r.code -eq 201 -and $pur.purchaseNo -eq "PUR-00001" -and $pur.taxable -eq 1250 -and ($pur.cgst + $pur.sgst) -eq 129 -and $pur.total -eq 1379)
    $s = Stock $own; T "same batch adds to stock, free units included (10 + 12 = 22)" ($s["P1"] -eq 22)
    T "a new batch gets its own stock row (P1-NEWB = 5)" ($s["P1-NEWB"] -eq 5)
    $newId = ($pur.items | Where-Object { $_.name -eq "Brand New Syrup" }).medicineId
    T "a medicine typed by name is created (10 units)" ($newId -and $s[$newId] -eq 10)
    $r = Call GET "/medicines/P1-NEWB" $null $own; T "new batch keeps its own expiry and MRP" ($r.body.expiryDate -eq "2029-06-01" -and $r.body.price -eq 110)
    $bal = (Call GET "/suppliers/$sup" $null $own).body.supplier.balance; T "supplier balance is 1279 (1379 less 100 paid)" ($bal -eq 1279)
    $r = Call POST "/purchases" $pBody $own; T "same purchase retried: same record, nothing doubled" ($r.code -eq 200 -and $r.body.purchase.id -eq $pur.id -and (Stock $own)["P1"] -eq 22)
    $copy = @{ supplierId = $sup; billNo = "a-100"; billDate = $today; items = @(@{ name = "X"; batch = "B"; expiry = "2029-01-01"; mrp = 10; cost = 5; gstRate = 5; quantity = 1 }) }
    $r = Call POST "/purchases" $copy $own; T "same supplier bill number entered twice refused (409)" ($r.code -eq 409)
    $r = Call POST "/suppliers/$sup/payments" @{ amount = 10; mode = "credit" } $own; T "bad payment mode refused (400)" ($r.code -eq 400)
    $r = Call POST "/suppliers/$sup/payments" @{ amount = 279; mode = "bank"; note = "NEFT" } $own; T "payment to supplier: balance 1000" ($r.code -eq 201 -and $r.body.balance -eq 1000)
    $r = Call GET "/purchases/medicines?search=purchase" $null $cash; T "cashier cannot see cost prices (403)" ($r.code -eq 403)
    $r = Call GET "/purchases/medicines?search=purchase" $null $own; $hit = @($r.body | Where-Object { $_.id -eq "P1" })[0]
    T "average cost with free units: 600 / 22 = 27.27" ($hit.cost -eq 27.27)
    $r = Call GET "/medicines/P1" $null $cash; T "the normal medicine API does not reveal cost" ($r.code -eq 200 -and $null -eq $r.body.cost -and $null -eq $r.body.costPrice)
    $m2 = Med "C2" "Cost Test" 5 50 5; $m2.costPrice = "31.5"
    Call POST "/medicines" $m2 $own | Out-Null
    $hit = @((Call GET "/purchases/medicines?search=cost%20test" $null $own).body)[0]; T "cost price can be typed when adding a medicine" ($hit.cost -eq 31.5)
    $m2.price = "55"; $m2.Remove("costPrice")
    Call PUT "/medicines/C2" $m2 $own | Out-Null
    $hit = @((Call GET "/purchases/medicines?search=cost%20test" $null $own).body)[0]; T "editing without a cost keeps the old cost" ($hit.cost -eq 31.5 -and $hit.mrp -eq 55)

    "== Profit =="
    $before = (Call GET "/reports/summary" $null $own).body.profit
    $r = Call POST "/invoices" @{ items = @(@{ medicineId = "P1"; quantity = 1 }) } $own; $pi = $r.body.invoice
    $after = (Call GET "/reports/summary" $null $own).body.profit
    T "selling 1 at MRP 100 (taxable 89.29, cost 27.27) adds 62.02 profit" ([math]::Round($after.gross - $before.gross, 2) -eq 62.02)
    $r = Call POST "/invoices/$($pi.id)/return" @{ items = @(@{ itemId = $pi.items[0].itemId; quantity = 1 }) } $own
    $back = (Call GET "/reports/summary" $null $own).body.profit
    T "returning it to stock takes the profit back out" ([math]::Round($back.gross - $before.gross, 2) -eq 0)
    $sum = (Call GET "/reports/summary" $null $own).body
    T "summary shows purchases and input GST (1250 + 129)" ($sum.purchases.taxable -eq 1250 -and ($sum.purchases.cgst + $sum.purchases.sgst) -eq 129)
    $x = GetRaw "/reports/export?type=purchases" $own; T "purchase register CSV: header + 1 purchase, with supplier GSTIN" ($x.code -eq 200 -and (Lines $x).Count -eq 2 -and $x.text -match "27ABCDE1234F1Z5")
    $x = GetRaw "/reports/export?type=supplier-dues" $own; T "supplier dues CSV lists Alpha Pharma" ($x.code -eq 200 -and $x.text -match "Alpha Pharma")
    $x = GetRaw "/reports/export?type=profit" $own; T "profit CSV lists the medicine" ($x.code -eq 200 -and $x.text -match "Purchase Test Tablet")

    "== Cancelling a purchase =="
    $r = Call POST "/invoices" @{ items = @(@{ medicineId = "P1-NEWB"; quantity = 1 }) } $own; $pi2 = $r.body.invoice
    $r = Call POST "/purchases/$($pur.id)/cancel" $null $own
    T "cannot cancel a purchase whose stock was sold (409)" ($r.code -eq 409)
    T "...and nothing changed (P1 still 22)" ((Stock $own)["P1"] -eq 22)
    Call POST "/invoices/$($pi2.id)/return" @{ items = @(@{ itemId = $pi2.items[0].itemId; quantity = 1 }) } $own | Out-Null
    $r = Call GET "/purchases/$($pur.id)" $null $other; T "another shop cannot open this purchase (404)" ($r.code -eq 404)
    $r = Call POST "/purchases/$($pur.id)/cancel" $null $other; T "another shop cannot cancel it (404)" ($r.code -eq 404)
    $r = Call GET "/suppliers/$sup" $null $other; T "another shop cannot open this supplier (404)" ($r.code -eq 404)
    $r = Call POST "/suppliers/$sup/payments" @{ amount = 10 } $other; T "another shop cannot pay this supplier (404)" ($r.code -eq 404)
    $r = Call POST "/purchases/$($pur.id)/cancel" $null $cash; T "cashier cannot cancel a purchase (403)" ($r.code -eq 403)
    $r = Call POST "/purchases/$($pur.id)/cancel" $null $own; $s = Stock $own
    T "cancel takes the stock back out (P1 10, new batch 0)" ($r.code -eq 200 -and $s["P1"] -eq 10 -and $s["P1-NEWB"] -eq 0)
    T "ledger after cancel: 279 paid later stays as an advance (-279)" ((Call GET "/suppliers/$sup" $null $own).body.supplier.balance -eq -279)
    $r = Call POST "/purchases/$($pur.id)/cancel" $null $own; T "cancelling twice refused (409)" ($r.code -eq 409)
    $r = Call DELETE "/suppliers/$sup" $null $own; T "supplier with history cannot be deleted (409)" ($r.code -eq 409)

    "== A bill can never be saved twice =="
    $ref = [guid]::NewGuid().ToString()
    $b1 = Call POST "/invoices" @{ clientRef = $ref; items = @(@{ medicineId = "P1"; quantity = 2 }) } $own
    $b2 = Call POST "/invoices" @{ clientRef = $ref; items = @(@{ medicineId = "P1"; quantity = 2 }) } $own
    T "same bill retried returns the first one, stock reduced once" ($b1.code -eq 201 -and $b2.code -eq 200 -and $b1.body.invoice.invoiceNo -eq $b2.body.invoice.invoiceNo -and (Stock $own)["P1"] -eq 8)

    "== Activity log =="
    $r = Call GET "/audit" $null $cash; T "cashier cannot read the activity log (403)" ($r.code -eq 403)
    $log2 = (Call GET "/audit?limit=500" $null $own).body.entries
    $acts = @($log2 | ForEach-Object { $_.action })
    T "log holds bills, cancels, returns, stock, payments, sign-ins" (($acts -contains "invoice.create") -and ($acts -contains "invoice.cancel") -and ($acts -contains "invoice.return") -and ($acts -contains "purchase.create") -and ($acts -contains "purchase.cancel") -and ($acts -contains "medicine.add") -and ($acts -contains "customer.payment") -and ($acts -contains "supplier.payment") -and ($acts -contains "team.add") -and ($acts -contains "account.signin"))
    $e = @($log2 | Where-Object { $_.action -eq "customer.payment" -and $_.user -eq "cashier1" })[0]
    T "each entry says who did it" ($e.user -eq "cashier1" -and $e.role -eq "cashier" -and $e.detail -match "Received")
    T "failed actions are not logged as done" (@($log2 | Where-Object { $_.action -eq "purchase.cancel" }).Count -eq 1)
    T "another shop's log is separate" (@((Call GET "/audit" $null $other).body.entries | Where-Object { $_.user -eq "own_a" }).Count -eq 0)
    $r = Call POST "/audit" @{ action = "x" } $own; T "the log cannot be written through the API (405)" ($r.code -eq 405)

    "== Download all my data =="
    $r = Call GET "/backup/export" $null $cash; T "cashier cannot download the shop's data (403)" ($r.code -eq 403)
    $zipFile = Join-Path $work "export.zip"
    Invoke-WebRequest "$base/backup/export" -Headers @{ Authorization = "Bearer $own" } -OutFile $zipFile -UseBasicParsing
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($zipFile)
    $names = @($zip.Entries | ForEach-Object { $_.Name })
    $allText = ($zip.Entries | ForEach-Object { $rd = New-Object IO.StreamReader($_.Open()); $rd.ReadToEnd(); $rd.Dispose() }) -join "`n"
    $zip.Dispose()
    T "ZIP holds every part of the shop's records" (($names -contains "medicines.csv") -and ($names -contains "invoices.csv") -and ($names -contains "customer_ledger.csv") -and ($names -contains "purchases.csv") -and ($names -contains "activity_log.csv"))
    T "ZIP has this shop's data and nothing from another shop" (($allText -match "Purchase Test Tablet") -and ($allText -notmatch "Expired Test"))
    T "ZIP never contains passwords" ($allText -notmatch "pbkdf2" -and $allText -notmatch "password")

    "== Accounts and safety =="
    $r = Call POST "/auth/signup" @{ username = "newbie"; email = "newbie@test.com"; password = "GoodPass123" }; T "signup without accepting the Terms refused (400)" ($r.code -eq 400)
    $r = Call POST "/auth/signup" @{ username = "newbie"; email = "newbie@test.com"; password = "alllettersonly"; acceptTerms = $true }; T "password without a number refused (400)" ($r.code -eq 400)
    $h = Invoke-WebRequest "$base/health" -UseBasicParsing
    T "safe headers on API responses" ($h.Headers["X-Content-Type-Options"] -eq "nosniff" -and $h.Headers["X-Frame-Options"] -eq "DENY")

    "== Plans =="
    $r = Call GET "/public"; T "prices are public (no sign-in)" ($r.code -eq 200 -and $r.body.trialDays -eq 14)
    $r = Call GET "/plan" $null $own; T "new shop is on a 14 day trial" ($r.body.state -eq "trial" -and $r.body.canWrite -eq $true -and $r.body.daysLeft -eq 14)
    $r = Call GET "/plan"; T "plan needs a login (401)" ($r.code -eq 401)
    $boss = (Call POST "/auth/login" @{ username = "platform"; password = "AdminPass1" }).body
    T "platform admin is recognised at sign-in, a shop owner is not" ($boss.admin -eq $true -and (Call POST "/auth/login" @{ username = "own_a"; password = "OwnerPass1" }).body.admin -eq $false)
    $r = Call GET "/admin/shops" $null $own; T "a shop owner cannot open the admin list (403)" ($r.code -eq 403)
    $r = Call GET "/admin/shops"; T "admin list needs a login (401)" ($r.code -eq 401)
    $r = Call PUT "/profile" @{ username = "own_a"; email = "boss@test.com" } $own; T "nobody can change their email to an admin address (403)" ($r.code -eq 403)
    $r = Call PUT "/profile" @{ username = "own_a"; email = "stranger@test.com" } $own
    $still = (Call GET "/profile" $null $own).body.email
    T "a new email is not saved until a code sent to it is confirmed" ($r.code -ne 200 -and $still -eq "own_a@test.com")
    $r = Call GET "/admin/shops" $null $boss.token; $mine = @($r.body.shops | Where-Object { $_.owner -eq "own_a" })[0]
    T "admin sees every shop with owner and counts" ($r.code -eq 200 -and $mine.id -gt 0 -and $mine.bills -gt 0 -and $r.body.summary.shops -ge 3)
    $r = Call POST "/admin/shops/$($mine.id)" @{ trialEnds = "2020-01-01" } $boss.token; T "admin can end a trial" ($r.code -eq 200)
    $r = Call GET "/plan" $null $own; T "the shop now shows as ended" ($r.body.state -eq "expired" -and $r.body.canWrite -eq $false)
    $r = Call POST "/invoices" @{ items = @(@{ medicineId = "P1"; quantity = 1 }) } $own; T "an ended plan cannot make bills (402)" ($r.code -eq 402)
    $r = Call POST "/customers" @{ name = "Blocked" } $cash; T "...nor can its staff change anything (402)" ($r.code -eq 402)
    T "...but all data can still be read" ((Call GET "/medicines" $null $own).code -eq 200 -and (Call GET "/invoices" $null $own).code -eq 200)
    $x = GetRaw "/backup/export" $own; T "...and still downloaded" ($x.code -eq 200)
    $r = Call POST "/admin/shops/$($mine.id)" @{ extendDays = 30 } $boss.token
    $r = Call GET "/plan" $null $own; T "admin adds 30 paid days: shop is active" ($r.body.state -eq "active" -and $r.body.daysLeft -eq 30)
    $r = Call POST "/invoices" @{ items = @(@{ medicineId = "P1"; quantity = 1 }) } $own; T "billing works again" ($r.code -eq 201)
    $r = Call POST "/admin/shops/$($mine.id)" @{ paidUntil = ""; trialEnds = (Get-Date).AddDays(-1).ToString("yyyy-MM-dd") } $boss.token
    $r = Call GET "/plan" $null $own; T "1 day past the end is a grace period, work continues" ($r.body.state -eq "grace" -and $r.body.canWrite -eq $true)
    $r = Call POST "/admin/shops/$($mine.id)" @{ disabled = $true } $boss.token
    $r = Call GET "/medicines" $null $own; T "a suspended shop is locked out (403)" ($r.code -eq 403)
    $r = Call POST "/admin/shops/$($mine.id)" @{ disabled = $false; trialEnds = (Get-Date).AddDays(14).ToString("yyyy-MM-dd") } $boss.token
    $r = Call GET "/medicines" $null $own; T "un-suspending restores access" ($r.code -eq 200)
    $r = Call POST "/admin/shops/99999" @{ extendDays = 1 } $boss.token; T "unknown shop (404)" ($r.code -eq 404)
    $r = Call POST "/admin/shops/$($mine.id)" @{ extendDays = 0 } $boss.token; T "extending by 0 days refused (400)" ($r.code -eq 400)

    "== Free launch (no charging date set) =="
    Stop-TestServer
    $script:paidStart = ""
    Start-TestServer
    $r = Call GET "/public"; T "the public page knows it is free" ($r.body.free -eq $true)
    $r = Call GET "/plan" $null $own; T "every shop is free, with no end date" ($r.body.state -eq "free" -and $r.body.canWrite -eq $true -and $r.body.endsOn -eq "")
    Call POST "/admin/shops/$($mine.id)" @{ trialEnds = "2020-01-01" } $boss.token | Out-Null
    $r = Call POST "/customers" @{ name = "Free Launch Customer" } $own; T "an old trial date blocks nothing while it is free" ($r.code -eq 201)
    $r = Call POST "/admin/shops/$($mine.id)" @{ disabled = $true } $boss.token
    $r = Call GET "/medicines" $null $own; T "the admin can still suspend a shop during the free period (403)" ($r.code -eq 403)
    Call POST "/admin/shops/$($mine.id)" @{ disabled = $false } $boss.token | Out-Null

    "== Backup and restore drill =="
    Stop-TestServer
    $env:DB_PATH = $dbFile
    $drill = @(java --enable-native-access=ALL-UNNAMED -cp $cp BackupDrill 2>&1 | Where-Object { $_ -notmatch "^WARNING" })
    Remove-Item Env:DB_PATH
    $drill | ForEach-Object { "   $_" }
    T "a backup restores with every bill, customer and purchase, and passes the integrity check" (($drill -join "`n") -match "RESTORE OK")

    $log = @($script:earlierLog) + @(Get-Content $script:serverLog -ErrorAction SilentlyContinue) | Where-Object { $_ -notmatch "^WARNING" -and $_ -notmatch "sendResponseHeaders" -and $_.Trim() }
    T "no errors in the server log" (@($log).Count -eq 0)
}
finally {
    Stop-TestServer
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}

""
"RESULT: $script:pass passed, $script:fail failed"
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
