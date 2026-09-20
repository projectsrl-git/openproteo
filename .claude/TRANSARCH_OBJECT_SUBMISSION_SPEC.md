# Transarch — Object submission format (reconstructed reference)

Source: UBS Confluence, *Transaction Record Archive - Onboarding Home / Submission Formats / Object
(e.g. pdf, jpeg, xml, json)*, `pageId=1221950108`. Created by Roopa Rugmini Trikaripur Radhakrishnan,
last modified by Marcel Gautschi on May 27, 2026. 5 774 views at the time of capture.

**This file is a transcription from screenshots, not the source of truth.** It exists because the
Confluence page is unreachable from where this executor is developed, and because a spec that cannot
be read during implementation cannot be implemented against. Where the page contradicts itself the
contradiction is recorded here rather than resolved — see §6. Re-check against Confluence before
relying on any single line.

Scope note: OpenProteo's `objpack` targets the **TAR-packaged** path only ("Applications submitting
via MFT or Credit Suisse applications"), which is the "applicazioni ex CS" case. The non-TAR path is
transcribed for contrast because several constraints differ between the two.

---

## 1. Introduction

The system supports the ingestion of Objects in different formats — pdf, jpeg, xml, json, others —
as long as they conform to the records management requirements (*Accepted file formats and MIME
types*). TransArch ingestions are based upon this structure: **if a submission does not follow the
format it will fail ingestion validation.** Submission files must adhere to the naming convention and
the structure specified.

There are two submission formats.

### Non-TAR-packaged submissions — applications submitting via AzCopy and Axway

Mandatory files submitted to TransArch:

- `*.audit.json` — required for validation record count and the names of the provided files
- `*.metadata.csv` — describing metadata for the objects
- `*.file1`, `*.file2`, `*.file3` — objects
- `*.control` — empty control file to trigger ingestion

### TAR-packaged submissions — applications submitting via MFT or Credit Suisse applications

This format consists of the following mandatory files and requires to be **packaged as a `*.tar`
file** before being submitted to the system.

`*.tar` → package file with the following content:

- `*.audit.json` — required for validation record count and the names of the submission data files
- `*.metadata.csv` — describing metadata for the objects
- `*.file1`, `*.file2`, `*.file3` — objects to be archived
- `*.control` — empty control file to trigger ingestion

`*.md5` → checksum file for validating the data file within transmission to UBS.

**The `.md5` is NOT inside the tar.** The page's own diagram makes this explicit: the box
`tf0000001.20240101.S001.V1.tar` encloses the audit, metadata, object and control files, while
`tf0000001.20240101.S001.V1.md5` sits outside it, as a sibling of the tar. The legend lists five
roles: TAR Pkg, Audit File, Metadata, Data Files, Control File, MD5 File.

### Constraints and limitations

**Non-TAR-packaged:**

- A submission can be not bigger than 500 GB — subject to the limitations of the chosen file transfer type (azcopy, blob-to-blob)
- Submission limitations per day are 500 GB if each object file size is under 2 GB
- Each object file must map to 1 record (multiple records cannot be mapped to a single object file)
- A submission must include data from within the last 10 months as of the ingestion date, except for yearly submissions, which may include data from the last 13 months
- A submission cannot contain more than 100K objects
- Each object in the submission must not exceed 2 GB

**TAR-packaged, CS submissions:**

- A submission can be not bigger than 20 GB — limited by the CS to UBS Landing Zone File Transfer
- Multiple submissions can be sent per day; reach out to the onboarding team if more than 10 submissions per day of 20 GB each are planned
- A submission cannot contain more than 100K objects
- Each object in the submission must not exceed 2 GB

**TAR-packaged, MFT submissions:**

- A submission via MFT must not exceed 2 GB

---

## 2. Naming convention

The naming convention for metadata, audit, objects and control files must be adhered to for all
submissions. The format aims to minimise any possible confusion about "what a file is" and with which
submission it belongs without going through the audit files.

### Submission base name

The file name base is identical for all files in a submission:

| Pattern | Example |
|---|---|
| `<tf#>.<Transmission date>.S<Submission Sequence Number>.V<Submission Version>.*` | `tf0000001.20250101.S001.V001.*` |

**Elements:**

- **tf#** *[alpha-numeric field]* — the TransArch Feed ID, assigned by the Onboarding team during the onboarding process. Example: `tf0000001`
- **Transmission Date** *[date field]* — the intended transmission date. **If the file cannot be transmitted on this date for technical or other reasons, the field must not be changed.** Example: `20240101`
- **Submission Sequence Number** *[sequence field]* — a counter differentiating consecutive transfers for a given day, starting at 001. The preceding literal uppercase "S" indicates that this numeral is the submission sequence counter. Example: `S001`
- **Submission Version** *[sequence field]* — a counter differentiating multiple occurrences of a single submission, starting at 001 for each submission sequence number. The preceding literal uppercase "V" indicates the submission version sequence counter. If a submission has an error within the first submission, the new submission requires `V002`, indicating that data is corrected from the first submission `V001`. Example: `V001`

**NOTE:** the base name is required to be unique for each submission. If a file needs to be fixed and
resent, use a new Submission Version.

**Allowed characters:** lowercase or uppercase letters a-z / A-Z; numbers 0-9; `.` (period); `-`
(hyphen); `_` (underscore character).

**Date:** must contain a valid date in the format `YYYYMMDD`.

**Sequence and Version:** a monotonically increasing numeric field. Starts at 1, incremented by 1,
**left padded with zeros to the length of 3 digits**, e.g. `001`.

---

## 3. Submission content

### 3.1. Audit file

The purpose of this file is to capture the source application's intended contents of the submission.
It includes:

- **Submission transmission date** — date on which the submission is sent to the archive
- **Submission Sequence number** (see Submission Base Name)
- **Submission Version number** (see Submission Base Name)
- **Total submission record count**
- **Target destination** — the target destination `endpoint_url`. **ONLY REQUIRED FOR TAR-PACKAGED SUBMISSIONS (APPLICATIONS SUBMITTING VIA MFT OR CREDIT SUISSE APPLICATIONS)**
- **Object Files** — list of all the object files with their respective Object ID and Object mime type

**Target Destination** must follow the format:

| Format | Example |
|---|---|
| `https://landingstorageaccount.blob.core.windows.net/container` | `https://ubstat1ibamerlanding.blob.core.windows.net/001-tf0000001` |

Where:

- The landing storage account and the container are provided by the Onboarding team
- The landing storage account **must** match the region-division (emea-ib, etc.) and the environment (Te1, Te2, etc.)
- The container **must** be deployed in the landing zone

Ingestion validation will be made against this file to ensure the records that were sent are the same
as the ones received. Currently this is done using XML for older feeds that are in transition for
GAType 1C, but **for new onboardings the file must be JSON**.

#### 3.1.1. JSON audit file

| Format | Example |
|---|---|
| `submission_base_name.audit.json` | `tf0000001.20250101.S001.V001.audit.json` |

```json
{
    "transmission_date" : 20250131,
    "sequence_number" : "001",
    "version_number" : "001",
    "record_count" : 2,
    "TargetDestination": "https://ubstat1ibamerlanding.blob.core.windows.net/001-TF0000001",
    "metadata_file_name" : "tf0000001.20250101.S001.V001.metadata.csv",
    "submission_object_files" : [
        {
            "file_name" : "tf0000001.20250101.S001.V001.OID000001.pdf",
            "mime_type" : ".pdf",
            "object_id" : "1"
        },
        {
            "file_name" : "tf0000001.20250101.S001.V001.OID000002.jpeg",
            "mime_type" : ".jpeg",
            "object_id" : "2"
        }
    ]
}
```

Types are not uniform and the asymmetry is in the source, not in this transcription:
`transmission_date` and `record_count` are JSON **numbers**; `sequence_number`, `version_number` and
`object_id` are JSON **strings**. `TargetDestination` is the only key in PascalCase. The example's
`TargetDestination` carries the feed id uppercased (`001-TF0000001`) while §3.1's own Target
Destination example uses lowercase (`001-tf0000001`).

### 3.2. Metadata file (search attributes)

- The CSV file which will contain objects metadata should have **one row per object**. Each object must only be referenced by 1 and only 1 row in the metadata.csv (no many-to-one relationships)
- The objects should have a set schema which should contain information from the object that is relevant for searching
- No limit on number of columns or rows
- Name of the object file must be mentioned
- Some fields are mandatory and must follow the specified order

| Format | Example |
|---|---|
| `submission_base_name.metadata.csv` | `tf0000001.20250101.S001.V001.metadata.csv` |

**Format:**

- CSV text file using **UTF-8**, ending in `.csv`
- **RESERVED delimiter character(s)** — should not be used anywhere else in the file — and from a list of approved ones (see *Accepted Delimiters List*)
- The first row of the CSV file must contain the headers, which are **NOT changeable without prior agreement** (requires re-onboarding)
- Additional characters to make files "human" readable (spaces between delimiters or in headers) or extra line breaks, and must follow the CSV standard as per RFC 4180

**Mandatory fields** — must be in every submission, are **NOT nullable**, and must appear in the
stated order **starting from the first column** in the schema:

1. **Object ID** — the ID of the object within the submission. Must be an Integer, starts at 1 and must be in ascending order.
2. **Record Business Date** — the record creation date; used to assign retention to the record.
3. **Object Mime Type** — String. Must conform to the records management requirements (*Accepted file formats and MIME types*).
4. **Original Object Name** — String capturing the original name of the object file if it exists.

**NOTE:** the first columns should match the mandatory schema order, but the rest can be in any
order. This schema must not change once the feed goes live to PRODUCTION.

**IMPORTANT:** the schema will be validated; a specific schema with data type clarification needs to
be created — see *Data Schema for Validation*.

Metadata CSV example with `;` as delimiter:

```
object_id;record_business_date;mime_type;original_object_name;example_header_05;example_header_06;example_header_07
1;20250131;.pdf;statement_12345.pdf;example_value_05;example_value_06;example_value_07
2;20250131;.jpeg;attached_picture_from_web.jpeg;example_value_05;example_value_06;example_value_07
```

Example of the schema file for the metadata file:

```
{"name":"object_id","nullable": false,"type":"string" },
{"name":"record_business_date","nullable": false,"type":"string" },
{"name":"mime_type","nullable": false,"type":"string" },
{"name":"original_object_name","nullable": false,"type":"string" },
{"name":"example_header_05","nullable": true,"type":"string" },
{"name":"example_header_06","nullable": true,"type":"string" },
{"name":"example_header_07","nullable": true,"type":"string" }
```

`object_id` is declared `"type":"string"` in the schema while §3.2 requires it to be an Integer. Both
statements are in the source.

### 3.3. Object files

One file per object, all sent as part of the submission, with a specific name (referred to in the
metadata file) and from a defined list of mime types.

- Each object file refers to one row in the `.csv` metadata file
- For each submission package (containing audit, control, metadata, object files) the **record count mentioned in the audit file should match the number of object files** sent as part of that submission. Each object file sent as part of a particular submission package should have a unique name
- The naming format of the object file **must start with `submission_base_name`** and **must end with `object_id.file_format`**; extra information about the file can be added in between. For example: `tf0000001.20250101.S001.V001.monthly_report.OID2.pdf`
- The uniqueness in the name of each object file is rendered by the `object_id` (e.g. OID000001, OID000002, OID000003 and so on)
- The `object_id` must start at 1, and it is independent to the submission — every submission starts with 1 — and the `object_id` in the FILE name must include padding depending on the number of objects in the submission
- The padding on the OID must be assigned based on the number of files in the submission (for large submissions, longer padding to accommodate all objects; for smaller, a smaller padding). For example:
  - 5 objects → `OID1`, `OID2`, `OID3`, `OID4`, `OID5` — no extra zeros required
  - 62 objects → `OID01`, `OID02`, … , `OID61`, `OID62`
  - 137 objects → `OID001`, `OID002`, … , `OID098`, `OID099`, `OID100`, … , `OID136`, `OID137`

| Format | Example |
|---|---|
| `submission_base_name.object_id.pdf` | `tf0000001.20250101.S001.V001.OID1.jpeg` |
| | `tf0000001.20250101.S001.V001.monthly_report.OID2.pdf` |
| | `tf0000001.20250101.S001.V001.OID2147483647.jpeg` |
| | `tf0000001.20250101.S001.V001.OID0004.jpeg` |

### 3.4. Control file

The control file indicates to the archive system that a set of submission files has been delivered to
the archive system and is ready to be processed. It does not matter if this file arrives before or
after the other submission files.

**The file must have a 0-byte length.**

| Format | Example |
|---|---|
| `submission_base_name.control` | `tf0000001.20250101.S001.V001.control` |

### 3.5. TAR package and MD5 checksum file — applications submitting via MFT and Credit Suisse applications only

For TAR-packaged submissions, applications submitting via MFT and Credit Suisse applications sending
data via the CStoUBS Landingzone need to create a submission package and provide an MD5 checksum file
with it.

- → `tf0000001.20240101.S001.V1.tar`
- → `tf0000001.20240101.S001.V1.md5`

**TAR packages.** *Do* only use the following compressions for `*.tar` creations:

- Compress with **gzip (-z)** — the archive will be compressed using gzip, resulting in a `.tar.gz` file
- Compress with **bzip2 (-j)** — resulting in a `.tar.bz2` file
- Compress with **xz (-J)** — resulting in a `.tar.xz` file

**Do not** use any compressions like: any compression within a `.TAR` file in full that the file
ending is for example `*.zip`, `*.7z`.

**MD5 checksum file.** Out of the TAR file an MD5 checksum needs to be created and provided as a
separate file as below. **Checksum values in the MD5 file must be in lowercase.**

→ `tf0000001.20240101.S001.V1.md5`, content:

```
7a0c9705f220eb26859803f719dbdc45
```

The content is the bare hash. No file name, no `*` marker, no second field — i.e. **not** the
`md5sum` output format.

---

## 4. Validation

The following validations will apply to every submission.

**Submission names:**

- Every object file name in the submission matches their specified:
  - Submission Base Name vs file name
  - Object ID (based on Audit file)
  - Specified Mime Type in metadata against file name
- Submission abides by naming convention

**Submission content:**

- The number of Object files matches the Audit record count and the submission record count in the Metadata file
- Submission size is under max value (see Submission Specification)
- All object sizes are under max value (see Submission Specification)
- Object record business dates are within the last 10 months from submission date (13 months for yearly feeds)
- Audit file contains all mandatory fields for every object
- Metadata file schema matches declared format and contains mandatory fields (not Nullable)
- Metadata file contains Object record business date in correct format

---

## 5. Related pages referenced but not captured

- *Accepted file formats and MIME types* — `confluence/display/TRAON/Accepted+File+Formats+and+MIME+types`. A partial capture of an equivalent table is in the project files: PDF/A-1b/2/3 `application/pdf` (with the pass-through exception for externally received PDFs, RMCL decision #193 of 2021-12-13); XML `text/xml`; TXT `text/plain`; HTML `text/html`; JSON `application/json`; CSV `text/csv`; XLSX/XLS; MP3 `audio/mpeg`; MP4 `video/mp4`; TIFF `image/tiff` (CCITT G4 preferred); JPEG `image/jpeg`; EML `message/rfc822` for mail and webpages; DOCX **only accepted for CS applications currently part of the CS Winddown initiative**, and UBS apps are required to convert docx to PDF.
- *Accepted Delimiters List* — the approved metadata CSV delimiters. **Not captured; needed before the delimiter parameter can be validated rather than merely accepted.**
- *Data Schema for Validation* — how the metadata schema file is produced.
- *TAR Object Submission* — the different case where a TAR file is itself the archived object. Not this one.
- *Submission Specification* — the authoritative max sizes referenced by §4.

---

## 6. Internal contradictions in the source

Recorded, not resolved. Each is a Gate 0 question in `OBJECT_PACKAGE_EXECUTOR.md`.

1. **Version padding.** §2 requires sequence and version left-padded to 3 digits (`V001`), and §3.1.1 / §3.2 / §3.3 / §3.4 examples all use `V001`. §3.5's own examples use **`V1`** for both the tar and the md5. The two cannot both be right for the same submission, since §2 states the base name is identical for all files in a submission.
2. **OID padding.** §3.3 mandates padding derived from the object count (5 objects → `OID1`). The audit example in §3.1.1 uses `OID000001` for a 2-object submission, which by that rule should be `OID1`. The §3.3 example table also shows `OID0004` and an unpadded `OID2147483647`.
3. **Mime type.** §3.2 requires Object Mime Type to conform to the records management requirements, which are real media types (`application/pdf`). Every example in §3.1.1 and §3.2 instead carries a **dotted extension** (`.pdf`, `.jpeg`), and §4 validates "Specified Mime Type in metadata against file name", which only works mechanically for the extension form.
4. **Compression.** §3.5 lists gzip / bzip2 / xz under "**Do** only use following compressions". **Not a contradiction, on a closer reading:** the same bullets name the resulting files — `.tar.gz`, `.tar.bz2`, `.tar.xz` — and §2's pattern ends in `.*`, so those names satisfy the naming convention and the `.tar` examples elsewhere are the uncompressed case. What is genuinely unstated is whether compression is *expected* or merely *tolerated*, and no submission has been delivered compressed so far.
5. **object_id type.** Integer per §3.2, `"type":"string"` in the schema example, JSON string in the audit example.
