# release-notes

This repository stores the marketing team’s release notes in Git so builds do not depend on the website. Each update creates a commit hash that downstream jobs use to ensure reproducible documentation.

---

## Repository Structure

- **`fetchReleaseNotes.sh`**  
  Script to fetch the latest release notes, validate them, compute checksums, and commit changes.

- **`manifest.txt`**  
  Generated metadata file that records:
  - Source URL
  - UTC timestamp
  - SHA-256 checksum of the downloaded notes

- **`<release-notes-file>.pdf`**  
  The actual release notes file, named exactly as provided by the server (`curl -OJ`).

---

## Script: `fetchReleaseNotes.sh`

- **Fetching**
  - Uses `curl` with retry logic to download from the marketing URL.
  - Preserves the server-provided filename for consistency.

- **Validation**
  - Ensures the file exists and is not empty.
  - Can be configured to exit gracefully if the site is down, so downstream builds use the last good commit.

- **Checksum Generation**
  - Computes SHA-256 of the file.
  - Provides a reproducibility guarantee (two builds can assert identical inputs).

- **Manifest Creation**
  - Overwrites `manifest.txt` with:
    - Source URL
    - UTC fetch timestamp
    - SHA-256 checksum

- **Git Commit & Push**
  - Stages the PDF and manifest.
  - Commits only if content changed.
  - Pushes to the `main` branch.

- **Commit Hash Output**
  - Prints and saves the commit SHA into `notes-sha.txt`.
  - Published as a TeamCity artifact for downstream builds.

---

## TeamCity Pipeline

- **Build Config**: `UpdateReleaseNotes`
- **VCS Root**: This repository (`main` branch)
- **Build Step**: Run `fetchReleaseNotes.sh`
- **Artifacts Published**:
  - Latest PDF release notes file
  - `manifest.txt`
  - `notes-sha.txt`

---

## How the Commit Hash Is Used

- Downstream job (`Vendor notes into parent repo`) declares an artifact dependency on `notes-sha.txt`.
- The vendoring script (`vendorNotes.sh`) reads the commit hash.
- Vendors the file from this repo at the exact commit.
- Guarantees downstream builds always use the same file version.

---

## Why This Approach?

- Marketing website is volatile (files may change or be unavailable).
- Mirroring in Git provides:
  - Stability — builds never fail due to external downtime.
  - Reproducibility — commits tie builds to exact file content.
  - Auditability — manifest records when, what, and which hash was fetched.
