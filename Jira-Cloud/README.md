# Jira-Cloud Scripts

Minimal scripts to integrate Jira Assets (Cloud) with external data sources.

## Prerequisites
- Groovy installed (or a ScriptRunner-like runner)
- Network access to Jira Assets API and Databricks (if applicable)

## Environment Variables
Set these before running any script. Do not commit real values to git.

- Jira/Assets
  - `JIRA_ASSETS_BASE_URL` (optional; mock used if absent). Example:
    - `https://api.atlassian.com/jsm/assets/workspace/$JIRA_WORKSPACE_ID`
  - `JIRA_WORKSPACE_ID`
  - `JIRA_API_TOKEN`
  - `JIRA_USERNAME`

- Databricks
  - `DATABRICKS_CLIENT_ID`
  - `DATABRICKS_CLIENT_SECRET`
  - `DATABRICKS_TENANT_ID`
  - `DATABRICKS_RESOURCE_ID`
  - `DATABRICKS_WORKSPACE_URL`
  - `DATABRICKS_WAREHOUSE_ID`

## Quick start
```bash
# macOS (zsh)
export JIRA_ASSETS_BASE_URL="https://example.com/mock-assets/workspace/${JIRA_WORKSPACE_ID}"
export JIRA_WORKSPACE_ID="..."
export JIRA_API_TOKEN="..."
export JIRA_USERNAME="user@example.com"
export DATABRICKS_CLIENT_ID="..."
export DATABRICKS_CLIENT_SECRET="..."
export DATABRICKS_TENANT_ID="..."
export DATABRICKS_RESOURCE_ID="..."
export DATABRICKS_WORKSPACE_URL="https://adb-xxxx.azuredatabricks.net"
export DATABRICKS_WAREHOUSE_ID="..."

# Run a script (example)
# groovy "/Users/johnnes.melro/Documents/scriptrunner/Jira-Cloud/update assets fields API.groovy"
```

Tip: you can also pass variables inline for a single run:
```bash
JIRA_WORKSPACE_ID="..." JIRA_API_TOKEN="..." groovy "path/to/script.groovy"
```

## .env example
Create a `.env` file locally (do not commit). Example keys:
```dotenv
JIRA_ASSETS_BASE_URL=
JIRA_WORKSPACE_ID=
JIRA_API_TOKEN=
JIRA_USERNAME=
DATABRICKS_CLIENT_ID=
DATABRICKS_CLIENT_SECRET=
DATABRICKS_TENANT_ID=
DATABRICKS_RESOURCE_ID=
DATABRICKS_WORKSPACE_URL=
DATABRICKS_WAREHOUSE_ID=
```

## Security notes
- No secrets are stored in code; all sensitive data must be provided via environment variables.
- Default URL for Assets falls back to a mock domain if `JIRA_ASSETS_BASE_URL` is not set.
- Add `.env` to `.gitignore` and commit only a `.env.example`.

## Scripts

### employee_integration.groovy
- Purpose: Sync employees from a Databricks table into Jira Assets (Cloud) Employee object type.
- Inputs:
  - Env vars listed above (Jira/Databricks)
  - Databricks identifiers configured in-file: `catalog`, `schema`, `table` (replace the placeholders)
- Notes:
  - Uses AQL to find existing employee by E-mail
  - Compares current vs. new attributes and only updates differences
- Run:
```bash
groovy "/Users/johnnes.melro/Documents/scriptrunner/Jira-Cloud/employee_integration.groovy"
```

### release manager.groovy
- Purpose: Iterate project releases and update issues in each release (sets a single-choice Planned field and a Planning Date to today).
- Inputs (placeholders in-file):
  - `projectKey`
  - `plannedField` (custom field id or alias used by ScriptRunner .fields access)
  - `plannedOptionValue` (e.g., "Yes")
  - `planningDateField` (date field)
- Environment: Designed for Jira ScriptRunner (uses `get`, `post`, `put` helpers).
- Run: Use ScriptRunner Console or scheduled job in Jira Cloud.

### input_cf_options_with_releases.groovy
- Purpose: Keep a Single Choice custom field's options equal to the project's release names.
- Trigger: Version-created/updated/deleted event (expects `event.version` and `projectId`).
- Inputs (placeholders in-file):
  - `fieldKey` (e.g., `customfield_12345`)
  - `contextId` (the field context ID to manage options)
- Behavior:
  - Adds missing options for new releases
  - Deletes options that no longer have a corresponding release
- Environment: ScriptRunner for Jira Cloud.
- Run: Bind to a project version event listener or run manually in Console for a given `projectId` in the event context.

## Troubleshooting
- 401/403: Check `JIRA_API_TOKEN`/`JIRA_USERNAME` and Jira product access
- 404/Base URL: Set `JIRA_ASSETS_BASE_URL` correctly for your workspace
- Databricks errors: validate SQL Warehouse ID and workspace URL 