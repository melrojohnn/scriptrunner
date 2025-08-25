import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import groovy.transform.Field

// ====================== GENERAL CONFIGURATION ======================
@Field def jiraWorkspaceId = System.getenv('JIRA_WORKSPACE_ID')
@Field def jiraApiToken = System.getenv('JIRA_API_TOKEN')
@Field def jiraUsername = System.getenv('JIRA_USERNAME')
@Field def jiraBaseUrl = System.getenv('JIRA_ASSETS_BASE_URL') ?: "https://example.com/mock-assets/workspace/${jiraWorkspaceId}"
@Field def objectTypeId = 7 // Employee

@Field def relatedObjectTypes = [
    role: 6,
    manager: 7
]

@Field def CLIENT_ID = System.getenv('DATABRICKS_CLIENT_ID')
@Field def CLIENT_SECRET = System.getenv('DATABRICKS_CLIENT_SECRET')
@Field def TENANT_ID = System.getenv('DATABRICKS_TENANT_ID')
@Field def DATABRICKS_RESOURCE_ID = System.getenv('DATABRICKS_RESOURCE_ID')
@Field def DATABRICKS_WORKSPACE = System.getenv('DATABRICKS_WORKSPACE_URL')
@Field def TOKEN_BASE_URL = "${DATABRICKS_WORKSPACE}/oidc/v1/token"
@Field def DATABRICKS_STATEMENT_ENDPOINT = 'api/2.0/sql/statements/'
@Field def warehouse_id =  System.getenv('DATABRICKS_WAREHOUSE_ID')

// ====================== DATABRICKS CONFIGURATION (SECURE) ======================
@Field def catalog = "REPLACE_WITH_YOUR_CATALOG_NAME"
@Field def schema = "REPLACE_WITH_YOUR_SCHEMA_NAME"
@Field def table = "REPLACE_WITH_YOUR_TABLE_NAME"

// ====================== ATTRIBUTE MAPPING ======================
@Field def mapping = [
    collaborator_name: [id: 84, name: "Name"],
    email: [id: 89, name: "E-mail"],
    role_name: [id: 87, name: "Role", refType: "role"],
    manager_email: [id: 139, name: "Manager (PDM)", refType: "manager"]
]

// Attributes that allow multiple values
@Field def multiValueAttributes = [
    'Manager (PDM)'
]

// ====================== INTEGRATION FUNCTIONS ======================

def generateBearerToken() {
    def postData = [
        grant_type: 'client_credentials',
        client_id: CLIENT_ID,
        client_secret: CLIENT_SECRET,
        scope: 'all-apis'
    ].collect { k, v -> "${URLEncoder.encode(k, 'UTF-8')}=${URLEncoder.encode(v, 'UTF-8')}" }.join('&')

    def connection = new URL(TOKEN_BASE_URL).openConnection()
    connection.setRequestMethod("POST")
    connection.setDoOutput(true)
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    connection.outputStream.withWriter("UTF-8") { writer ->
        writer.write(postData)
    }
    connection.connect()
    if (connection.responseCode == 200) {
        def responseJson = new JsonSlurper().parse(connection.inputStream.newReader())
        logger.info("Token obtained successfully")
        return responseJson.access_token
    } else {
        def error = connection.errorStream?.text
        throw new RuntimeException("Error obtaining token: HTTP ${connection.responseCode} - ${error}")
    }
}

def executeQueryDatabricks(String queryJson, String tokenBearer) {
    def url = "${DATABRICKS_WORKSPACE}/${DATABRICKS_STATEMENT_ENDPOINT}"
    def connection = new URL(url).openConnection()
    connection.setRequestMethod("POST")
    connection.setDoOutput(true)
    connection.setRequestProperty("Authorization", "Bearer ${tokenBearer}")
    connection.setRequestProperty("Content-Type", "application/json")
    connection.outputStream.withWriter("UTF-8") { writer ->
        writer.write(queryJson)
    }
    connection.connect()
    if (connection.responseCode != 200) {
        def error = connection.errorStream?.text
        throw new RuntimeException("Error executing query: HTTP ${connection.responseCode} - ${error}")
    }
    def response = new JsonSlurper().parse(connection.inputStream.newReader())
    def statementId = response.statement_id
    def finalStatus = ['SUCCEEDED', 'FAILED']
    def status = response.status?.state
    while (!finalStatus.contains(status)) {
        logger.info("Query state: ${status}, waiting 5 seconds...")
        sleep(5000)
        def statusUrl = "${DATABRICKS_WORKSPACE}/api/2.0/sql/statements/${statementId}/"
        def statusConnection = new URL(statusUrl).openConnection()
        statusConnection.setRequestMethod("GET")
        statusConnection.setRequestProperty("Authorization", "Bearer ${tokenBearer}")
        statusConnection.connect()
        if (statusConnection.responseCode != 200) {
            def err = statusConnection.errorStream?.text
            throw new RuntimeException("Error checking query status: HTTP ${statusConnection.responseCode} - ${err}")
        }
        def statusResponse = new JsonSlurper().parse(statusConnection.inputStream.newReader())
        status = statusResponse.status?.state
        response = statusResponse
    }
    if (status == 'SUCCEEDED') {
        logger.info("Query executed successfully")
        return response
    } else {
        throw new RuntimeException("Query finished with status: ${status}")
    }
}

def fetchDatabricksRecords() {
    def statement = "SELECT * FROM ${catalog}.${schema}.${table} LIMIT 100"
    def queryMap = [
        warehouse_id: warehouse_id,
        catalog: catalog,
        schema: schema,
        disposition: "INLINE",
        statement: statement,
        parameters: []
    ]
    def queryJson = JsonOutput.toJson(queryMap)
    def tokenBearer = generateBearerToken()
    def response = executeQueryDatabricks(queryJson, tokenBearer)
    def columns = response.manifest?.schema?.columns?.collect { it.name }
    def rows = response.result?.data_array
    if (columns && rows) {
        return rows.collect { row ->
            [ : ].with { rec ->
                columns.eachWithIndex { col, idx -> rec[col] = row[idx] }
                rec
            }
        }
    } else {
        logger.error("Databricks API response does not contain the expected structured data.")
        return []
    }
}

def searchObjectKeyByEmail(email, jiraBaseUrl, jiraUsername, jiraApiToken, logger, boolean isEmail = true) {
    if (!email) return null
    def searchUrl = "${jiraBaseUrl}/v1/object/aql?startAt=0&maxResults=1"
    def field = isEmail ? "E-mail" : "Name"
    def qlQuery = "${field} = \"${email}\" AND objectTypeId = ${objectTypeId}"
    def requestBody = JsonOutput.toJson([qlQuery: qlQuery])
    try {
        def connection = new URL(searchUrl).openConnection() as HttpURLConnection
        connection.setRequestMethod("POST")
        connection.setRequestProperty("Authorization", "Basic ${"${jiraUsername}:${jiraApiToken}".bytes.encodeBase64().toString()}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.doOutput = true
        def writer = new OutputStreamWriter(connection.outputStream)
        writer.write(requestBody)
        writer.flush()
        writer.close()
        if (connection.responseCode == 200) {
            def response = new JsonSlurper().parseText(connection.inputStream.text)
            if (response?.values?.size() > 0) {
                def key = response.values[0].objectKey
                logger.info("🔍 Found Key for '${email}' (${field}): ${key}")
                return key
            }
        } else {
            def error = connection.errorStream?.text
            logger.error("❌ Error searching for '${email}' in Assets. HTTP code: ${connection.responseCode} - ${error}")
        }
    } catch (Exception e) {
        logger.error("❌ Error searching for '${email}': ${e.message}", e)
    }
    logger.warn("⚠️ Reference not found for '${email}' (${field}).")
    return null
}

// ====================== COMPARISON AND ATTRIBUTE MANAGEMENT ======================

def compareAttributes(existingObject, newAttributes) {
    def differences = []
    def currentAttributes = [:]
    existingObject?.attributes?.each { attr ->
        currentAttributes[attr.objectTypeAttributeId] = attr.objectAttributeValues?.collect { it.value } ?: []
    }
    newAttributes.each { newAttr ->
        def attrId = newAttr.objectTypeAttributeId
        def attrName = mapping.find { it.value.id == attrId }?.value?.name
        def newValues = newAttr.objectAttributeValues?.collect { it.value } ?: []
        def currentValues = currentAttributes[attrId] ?: []
        if (newValues.sort() != currentValues.sort()) {
            differences << [
                attributeId: attrId,
                attributeName: attrName,
                currentValues: currentValues,
                newValues: newValues
            ]
        }
    }
    return differences
}

def mergeAttributes(existingObject, newAttributes, differences) {
    def attributesMap = [:]
    if (existingObject?.attributes) {
        existingObject.attributes.each { attr ->
            attributesMap[attr.objectTypeAttributeId] = attr.objectAttributeValues?.clone() ?: []
        }
    }
    differences.each { diff ->
        def attrId = diff.attributeId
        def attrName = diff.attributeName
        def newValues = diff.newValues
        if (multiValueAttributes.contains(attrName)) {
            def existingValues = attributesMap[attrId] ?: []
            def mergedValues = newValues.collect { [value: it] } + existingValues
            attributesMap[attrId] = mergedValues.unique { it.value }
        } else {
            attributesMap[attrId] = newValues.collect { [value: it] }
        }
    }
    return attributesMap.collect { id, values -> [objectTypeAttributeId: id, objectAttributeValues: values] }
}

def createOrUpdateAssetObject(objectId, attributesPayload, isUpdate, jiraBaseUrl, jiraUsername, jiraApiToken, logger) {
    def url = isUpdate ? "${jiraBaseUrl}/v1/object/${objectId}" : "${jiraBaseUrl}/v1/object/create"
    def requestBody = JsonOutput.toJson(attributesPayload)
    def method = isUpdate ? "PUT" : "POST"
    logger.info("🔄 ${isUpdate ? 'Updating' : 'Creating'} object in Assets.")
    try {
        def connection = new URL(url).openConnection() as HttpURLConnection
        connection.setRequestMethod(method)
        connection.setRequestProperty("Authorization", "Basic ${"${jiraUsername}:${jiraApiToken}".bytes.encodeBase64().toString()}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.doOutput = true
        def writer = new OutputStreamWriter(connection.outputStream)
        writer.write(requestBody)
        writer.flush()
        writer.close()
        if (connection.responseCode == (isUpdate ? 200 : 201)) {
            def response = new JsonSlurper().parseText(connection.inputStream.text)
            logger.info("✅ Object ${isUpdate ? 'updated' : 'created'} successfully.")
            return response
        } else {
            def error = connection.errorStream?.text
            logger.error("❌ Error ${isUpdate ? 'updating' : 'creating'} object. HTTP code: ${connection.responseCode}, response: ${error}")
            return null
        }
    } catch (Exception e) {
        logger.error("❌ Error ${isUpdate ? 'updating' : 'creating'} object in Assets: ${e.message}", e)
        return null
    }
}

// ====================== MAIN EXECUTION ======================

def executeIntegration() {
    try {
        def records = fetchDatabricksRecords()
        logger.info("Total records returned from Databricks: ${records.size()}")

        records.each { record ->
            def email = record.email
            def name = record.collaborator_name ?: "No Name"
            if (!email) {
                logger.warn("Record without email for collaborator '${name}', skipping...")
                return
            }
            logger.info("Starting processing for collaborator: ${name} (Email: ${email})")

            def existingAsset = searchObjectKeyByEmail(email, jiraBaseUrl, jiraUsername, jiraApiToken, logger)
            def attributesPayload = [ workspaceId: jiraWorkspaceId, objectTypeId: objectTypeId, attributes: [] ]
            def newAttributes = []

            mapping.each { apiField, info ->
                def value = record[apiField]
                if (value) {
                    if (info.refType) {
                        def key = searchObjectKeyByEmail(value, relatedObjectTypes[info.refType], jiraBaseUrl, jiraUsername, jiraApiToken, logger)
                        if (key) {
                            newAttributes << [ objectTypeAttributeId: info.id, objectAttributeValues: [[value: key]] ]
                        } else {
                            logger.warn("⚠️ Reference not found for attribute '${info.name}': value '${value}'")
                        }
                    } else {
                        newAttributes << [ objectTypeAttributeId: info.id, objectAttributeValues: [[value: value]] ]
                    }
                }
            }

            if (existingAsset) {
                def differences = compareAttributes(existingAsset, newAttributes)
                if (differences.isEmpty()) {
                    logger.info("✅ No changes required for ${name} (${email}) - all values are up to date")
                    return
                }
                attributesPayload.attributes = mergeAttributes(existingAsset, newAttributes, differences)
                createOrUpdateAssetObject(existingAsset.id, attributesPayload, true, jiraBaseUrl, jiraUsername, jiraApiToken, logger)
            } else {
                attributesPayload.attributes = newAttributes
                createOrUpdateAssetObject(null, attributesPayload, false, jiraBaseUrl, jiraUsername, jiraApiToken, logger)
            }

            sleep(500)
            logger.info("Processing completed for collaborator: ${name} (Email: ${email})\n---")
        }
    } catch (Exception e) {
        logger.error("General integration error: ${e.message}", e)
    }
}

// Start integration
executeIntegration()
