# Jira Scripts Repository

This repository contains various Groovy scripts designed to automate and manage Jira tasks, updates, and Assets integrations. Each script includes detailed logging and error handling for robust operation. Sensitive data and project-specific identifiers have been replaced with placeholders for security.

---

## 1. Jira Assets Integration – Employee Sync

**File:** `jira_assets_employee_sync.groovy`  

**Purpose:**  
Synchronizes employee data from an external source (e.g., Databricks) to Jira Assets. Updates fields like name, email, role, and manager references. Supports multi-value attributes (e.g., Manager).  

**Features:**  
- Fetches data from an external API  
- Maps employee attributes to Jira Assets object type  
- Handles both creation of new objects and updates for existing ones  
- Compares existing attributes and updates only differences  
- Detailed logging for tracking changes  

**Sensitive Data Placeholders:**  
- `JIRA_WORKSPACE_ID`  
- `JIRA_API_TOKEN`  
- `JIRA_USERNAME`  
- `JIRA_ASSETS_BASE_URL`  
- Databricks credentials: `DATABRICKS_CLIENT_ID`, `DATABRICKS_CLIENT_SECRET`, `TENANT_ID`, `DATABRICKS_RESOURCE_ID`, `DATABRICKS_WORKSPACE_URL`  
- Table, catalog, schema replaced with placeholders  

---

## 2. Jira Release Field Sync – Single Choice Field

**File:** `release_field_sync.groovy`  

**Purpose:**  
Keeps a Jira single-choice custom field in sync with all releases in a project. Removes options for releases no longer present and creates options for new releases automatically.  

**Features:**  
- Fetches all releases for a given project  
- Lists current options in a custom field context  
- Deletes obsolete options  
- Creates missing options for new releases  
- Logs all operations and warnings  

**Sensitive Data Placeholders:**  
- `PROJECT_KEY`  
- `CUSTOMFIELD_KEY` (single choice field)  
- `CONTEXT_ID`  

---

## 3. Jira Planned Field Auto-Update

**File:** `planned_field_update.groovy`  

**Purpose:**  
Automatically updates the "Planned" field and associated "Planning Date" field for Jira issues in all releases of a project.  

**Features:**  
- Fetches all releases for a project  
- Searches issues by release using JQL  
- Updates specified fields to configured values  
- Handles multiple issues per release  
- Logs success and failure for each issue  

**Sensitive Data Placeholders:**  
- `PROJECT_KEY`  
- `PLANNED_FIELD` (custom field key)  
- `PLANNED_OPTION_VALUE` (e.g., "Yes")  
- `PLANNING_DATE_FIELD` (custom field key)  

---

## Notes

- All scripts are written in **Groovy** for Jira automation.  
- Logging uses `logger.info`, `logger.warn`, and `logger.error` for structured monitoring.  
- Placeholders should be replaced with environment variables or secure configuration before execution.  
- Multi-value attributes (like Manager or Career Advisor) are supported in Assets scripts.  
- Scripts do **not** contain sensitive data in the repository; all sensitive credentials and project-specific identifiers are replaced with placeholders.  
