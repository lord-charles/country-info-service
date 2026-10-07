# Exploring the SOAP provider with SoapUI

1. Download and install SoapUI Open Source from <https://www.soapui.org/downloads/soapui/>.
2. **File > New SOAP Project**
   - Project name: `CountryInfoService`
   - Initial WSDL: `http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso?WSDL`
   - Tick *Create sample requests for all operations*. Click **OK**.
3. The project shows two bindings, `CountryInfoServiceSoapBinding` (SOAP 1.1) and
   `CountryInfoServiceSoapBinding12`, each listing all operations (`CapitalCity`,
   `CountryISOCode`, `FullCountryInfo`, `ListOfCountryNamesByName`, ...).

## Step 4 of the brief: `CountryISOCode`

Request:

```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:web="http://www.oorsprong.org/websamples.countryinfo">
   <soapenv:Header/>
   <soapenv:Body>
      <web:CountryISOCode>
         <web:sCountryName>Tanzania</web:sCountryName>
      </web:CountryISOCode>
   </soapenv:Body>
</soapenv:Envelope>
```

Response:

```xml
<m:CountryISOCodeResponse xmlns:m="http://www.oorsprong.org/websamples.countryinfo">
  <m:CountryISOCodeResult>TZ</m:CountryISOCodeResult>
</m:CountryISOCodeResponse>
```

## Step 5 of the brief: `FullCountryInfo`

Request body: `<web:FullCountryInfo><web:sCountryISOCode>TZ</web:sCountryISOCode></web:FullCountryInfo>`

Response (abridged):

```xml
<m:FullCountryInfoResult>
  <m:sISOCode>TZ</m:sISOCode>
  <m:sName>Tanzania</m:sName>
  <m:sCapitalCity>Dar es Salaam</m:sCapitalCity>
  <m:sPhoneCode>255</m:sPhoneCode>
  <m:sContinentCode>AF</m:sContinentCode>
  <m:sCurrencyISOCode>TZS</m:sCurrencyISOCode>
  <m:sCountryFlag>http://www.oorsprong.org/WebSamples.CountryInfo/Flags/Tanzania.jpg</m:sCountryFlag>
  <m:Languages>
    <m:tLanguage><m:sISOCode>swa</m:sISOCode><m:sName>Swahili</m:sName></m:tLanguage>
  </m:Languages>
</m:FullCountryInfoResult>
```

## Provider behaviours discovered while exploring (and handled in code)

| Observation | Impact | Handling |
|---|---|---|
| Name lookup is **case-sensitive**: `kenya` and `KENYA` are not found, `Kenya` is | Raw user input fails | Normalise to sentence case (per the brief) |
| Multi-word names need **title case**: `United states` is not found, `United States` is | Strict sentence case breaks multi-word countries | Fall back to title case if sentence case is not found |
| "Not found" is a **normal response** (`No country found by that name`), not a SOAP fault | Naive clients store garbage as an ISO code | Validate the result against `^[A-Z]{2}$` |
| Unknown ISO code returns an **empty record** with `sName = "Country not found in the database"` | Same | Treat empty `sISOCode` as not found |
| Endpoint is plain HTTP | No transport encryption | Data is public; in a regulated environment route egress through a TLS-terminating proxy |
