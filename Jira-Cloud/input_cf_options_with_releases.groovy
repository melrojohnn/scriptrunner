import groovy.json.JsonOutput

// ======= CONFIGURATION =======
def fieldKey  = "customfield_XXXX"   // Your Single Choice field key (placeholder)
def contextId = "YYYYY"               // Field context ID (placeholder)

// ======= EVENT INPUT =======
def versionEvent = event?.version
def projectId    = versionEvent?.projectId

if (!projectId) {
    logger.warn("⚠️ No projectId in the event. Nothing to do.")
    return
}

// ======= 1) FETCH ALL PROJECT RELEASES =======
def versionsResp = get("/rest/api/3/project/${projectId}/versions").asObject(List)
if (versionsResp.status != 200) {
    logger.error("❌ Failed to fetch project ${projectId} versions: ${versionsResp.status}")
    logger.error(versionsResp.body?.toString())
    return
}
def versions = (versionsResp.body ?: []) as List<Map>
def versionNames = versions.collect { (it.name ?: "").trim() }.findAll { it } as Set

logger.info("🔹 Project ${projectId}: ${versionNames.size()} releases found.")

// ======= 2) FETCH ALL FIELD OPTIONS FOR CONTEXT =======
def options = []
def startAt = 0
def pageSize = 50

while (true) {
    def optResp = get("/rest/api/3/field/${fieldKey}/context/${contextId}/option?startAt=${startAt}&maxResults=${pageSize}")
        .asObject(Map)
    if (optResp.status != 200) {
        logger.error("❌ Error listing options (startAt=${startAt}): ${optResp.status}")
        logger.error(optResp.body?.toString())
        return
    }
    def body = optResp.body ?: [:]
    def pageValues = (body.values ?: []) as List
    options.addAll(pageValues)
    def isLast = body.isLast in [true, "true"]
    if (isLast || pageValues.isEmpty()) break
    startAt += pageSize
}

logger.info("🔹 Field ${fieldKey}, context ${contextId}: ${options.size()} current options.")

// Useful mappings
def optionByValue = options.collectEntries { o -> [ (String.valueOf(o.value).trim()) : o ] }
def optionValues  = optionByValue.keySet()

// ======= 3) DELETE OPTIONS THAT NO LONGER HAVE A RELEASE =======
def toDelete = optionValues.findAll { !versionNames.contains(it) }
toDelete.each { val ->
    def opt = optionByValue[val]
    try {
        logger.info("🗑️ Deleting option without corresponding release: '${val}' (id=${opt.id})")
        delete("/rest/api/3/field/${fieldKey}/context/${contextId}/option/${opt.id}").asString()
    } catch (Exception e) {
        logger.error("❌ Error deleting option '${val}': ${e.message}")
    }
}

// ======= 4) CREATE OPTIONS FOR RELEASES NOT YET IN THE FIELD =======
def toCreate = versionNames.findAll { !optionValues.contains(it) }
toCreate.each { val ->
    def payload = [
        options: [[ value: val, disabled: false ]]
    ]
    def resp = post("/rest/api/3/field/${fieldKey}/context/${contextId}/option")
        .header("Content-Type", "application/json")
        .body(JsonOutput.toJson(payload))
        .asObject(Map)
    if (resp.status in [200,201]) {
        logger.info("✅ Option created: '${val}'")
    } else if (resp.status == 400 && (resp.body?.errorMessages?.join(' ') ?: '').toLowerCase().contains('already exists')) {
        logger.info("ℹ️ Option '${val}' already exists (race condition). Ignoring.")
    } else {
        logger.warn("⚠️ Failed to create option '${val}': ${resp.status} - ${resp.body}")
    }
}

logger.info("🎉 Synchronization completed for project ${projectId}.")
