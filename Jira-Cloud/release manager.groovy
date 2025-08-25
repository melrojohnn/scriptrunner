import groovy.json.JsonOutput
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// === CONFIGURATION ===
def projectKey = "PROJECT_KEY"                 // placeholder for the project key
def plannedField = "CUSTOMFIELD_PLANNED"       // placeholder for the "Planned" custom field
def plannedOptionValue = "YES_OPTION"          // placeholder for planned option value
def planningDateField = "CUSTOMFIELD_PLANNING_DATE" // placeholder for the planning date field
def today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)

// Fetch all releases for the project
def versionsResponse = get("/rest/api/3/project/${projectKey}/versions")
    .asObject(List)

if (versionsResponse.status != 200) {
    println("❌ Error fetching project ${projectKey} releases: ${versionsResponse.status}")
    return
}

def versions = versionsResponse.body
println("🔹 Found ${versions.size()} releases in project ${projectKey}.\n")

versions.each { version ->
    println("🔹 Processing release: ${version.name} (ID: ${version.id})")

    // Fetch issues for the release using POST /search/jql
    def jqlPayload = [
        jql: "project = '${projectKey}' AND fixVersion = '${version.name}'",
        fields: [plannedField, planningDateField],
        maxResults: 50
    ]

    def issuesResponse = post("/rest/api/3/search/jql")
        .header("Content-Type", "application/json")
        .body(JsonOutput.toJson(jqlPayload))
        .asObject(Map)

    if (issuesResponse.status != 200) {
        println("❌ Error fetching issues for release '${version.name}': ${issuesResponse.status}")
        return
    }

    def issues = issuesResponse.body.issues
    if (!issues) {
        println("⚠️ No issues found for release '${version.name}'\n")
        return
    }

    issues.each { issue ->
        println("🔹 Processing issue ${issue.key} - Planned: ${issue.fields[plannedField]}")

        // Update the fields
        def payload = [
            fields: [
                (plannedField): [value: plannedOptionValue],
                (planningDateField): today
            ]
        ]

        def updateResponse = put("/rest/api/3/issue/${issue.key}")
            .header("Content-Type", "application/json")
            .body(JsonOutput.toJson(payload))
            .asObject(Map)

        if (updateResponse.status == 204) {
            println("✅ Issue ${issue.key} updated: ${plannedField} = ${plannedOptionValue}, ${planningDateField} = ${today}")
        } else {
            println("❌ Error updating issue '${issue.key}': ${updateResponse.status}")
            println(updateResponse.body)
        }
    }
    println("\n")
}

println("✅ Processing completed for all releases.")
