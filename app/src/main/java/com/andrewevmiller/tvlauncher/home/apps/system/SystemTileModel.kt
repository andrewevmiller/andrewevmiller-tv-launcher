package com.andrewevmiller.tvlauncher.home.apps.system

/*
 * Pure (Android-free) model of the "Inputs & system" row: which tiles exist, in what order, with which
 * labels and which action. Everything here is unit-tested by purecheck; the Android glue
 * (TvInputSource, SystemActions, SystemTilesRow) only feeds it data and performs the actions.
 */

/** Which line glyph a tile shows. The Compose layer maps these to `FieldIcons`. */
enum class SystemIconKey { PORT, WIFI, SEARCH, GEAR }

/** Which system Settings screen a settings tile opens. */
enum class SettingsPanel { WIFI, MAIN }

/** What pressing a tile does. Performed by `SystemActions.launch`. */
sealed interface SystemTileAction {
    /** Switch to a passthrough input (HDMI, composite, ...) via the TIF passthrough channel URI. */
    data class PassthroughInput(val inputId: String) : SystemTileAction

    /** Open live TV for a built-in tuner input (tuners have no passthrough channel). */
    data class TunerInput(val inputId: String) : SystemTileAction

    data class OpenSettingsPanel(val kind: SettingsPanel) : SystemTileAction

    /** Open the assistant / search UI. */
    data object Search : SystemTileAction
}

data class SystemTile(
    /** Stable identity for lazy-list keys and focus survival. */
    val key: String,
    val label: String,
    val icon: SystemIconKey,
    val action: SystemTileAction,
)

/**
 * `android.media.tv.TvInputInfo.TYPE_*`, duplicated so this file stays Android-free.
 * `FrameworkConstantsTest` (runs on the owner's machine, not in purecheck) checks every value against
 * the real constants. Note TYPE_TUNER is 0 and TYPE_OTHER is 1000 (an earlier draft had them swapped; the
 * test caught it).
 */
object TvInputType {
    const val TUNER = 0
    const val OTHER = 1000
    const val COMPOSITE = 1001
    const val SVIDEO = 1002
    const val SCART = 1003
    const val COMPONENT = 1004
    const val VGA = 1005
    const val DVI = 1006
    const val HDMI = 1007
    const val DISPLAY_PORT = 1008
}

/**
 * What the Android layer learned about one `TvInputInfo`. Only [id], [type], [customLabel] and
 * [hdmiPort] are essential; the rest have defaults so tests and previews stay short.
 */
data class InputDescriptor(
    val id: String,
    val type: Int,
    /** The name the user gave the input in TV settings (`loadCustomLabel`), null if never renamed. */
    val customLabel: String?,
    /** HDMI port number when known (framework `HdmiDeviceInfo.portId`, else parsed from the system label). */
    val hdmiPort: Int?,
    /** The input is a child of another input, e.g. a CEC device sitting behind an HDMI port. */
    val hasParent: Boolean = false,
    /** The user hid the input in TV settings. */
    val hidden: Boolean = false,
    /** The providing TV input service belongs to a system app (false for user-installed channel apps). */
    val fromSystemApp: Boolean = true,
    /** The framework's own label (`loadLabel`), used when there is no custom label and no better name. */
    val systemLabel: String? = null,
)

/** Why an input did not get a tile. */
enum class InputDrop(val explanation: String) {
    HIDDEN("hidden by the user in TV settings"),
    HAS_PARENT("child of another input (CEC device behind a port); the parent port's tile covers it"),
    NOT_PHYSICAL("TYPE_OTHER or an unrecognised type: an app or virtual input, not a connector"),
    THIRD_PARTY_TUNER("tuner input from a user-installed app; channel apps are launched from the app grid"),
    DUPLICATE_ID("the same input id was reported twice"),
    DUPLICATE_PORT("another input already represents this HDMI port"),
}

/** Result of [selectPhysicalInputs]: the inputs that get a tile, and every input that did not, with why. */
data class InputSelection(
    val kept: List<InputDescriptor>,
    val dropped: List<Pair<InputDescriptor, InputDrop>>,
)

// ---------------------------------------------------------------------------------------------
// Which inputs are "physical"
// ---------------------------------------------------------------------------------------------

/** Connector types (composite ... DisplayPort) are numbered from 1001 in the framework. */
private const val FIRST_CONNECTOR_TYPE = 1001
private const val LAST_CONNECTOR_TYPE = 1999

fun isConnectorType(type: Int): Boolean = type in FIRST_CONNECTOR_TYPE..LAST_CONNECTOR_TYPE

/** Positive HDMI port number, or null when unknown (framework port ids start at 1; 0 and below mean "not set"). */
private fun knownPort(d: InputDescriptor): Int? =
    if (d.type == TvInputType.HDMI) d.hdmiPort?.takeIf { it > 0 } else null

/**
 * Per-input rule, first match wins. An input gets a tile only if none of these apply:
 *  1. [InputDescriptor.hidden]: the user hid it in TV settings.
 *  2. [InputDescriptor.hasParent]: it is a child entry (a CEC device behind an HDMI port). The port
 *     itself is listed on its own, and switching to the port reaches the device.
 *  3. Tuner (`TYPE_TUNER`): kept only when the service is a system app (the TV's own antenna/cable
 *     tuner). Tuners from user-installed channel apps are dropped; those apps live in the app grid.
 *  4. Connector types (1001..1999: composite, S-Video, SCART, component, VGA, DVI, HDMI, DisplayPort and
 *     any future one): kept.
 *  5. Everything else, including `TYPE_OTHER`: dropped. Those are app or virtual inputs, not something
 *     you plug a cable into.
 */
fun inputDropReason(d: InputDescriptor): InputDrop? = when {
    d.hidden -> InputDrop.HIDDEN
    d.hasParent -> InputDrop.HAS_PARENT
    d.type == TvInputType.TUNER -> if (d.fromSystemApp) null else InputDrop.THIRD_PARTY_TUNER
    isConnectorType(d.type) -> null
    else -> InputDrop.NOT_PHYSICAL
}

/**
 * Applies [inputDropReason], then removes duplicates, keeping the first occurrence in list order:
 * the same input id twice ([InputDrop.DUPLICATE_ID]), or two inputs claiming the same HDMI port number
 * ([InputDrop.DUPLICATE_PORT]; a port is one physical connector). HDMI inputs with an unknown port are
 * never collapsed against each other.
 */
fun selectPhysicalInputs(inputs: List<InputDescriptor>): InputSelection {
    val kept = ArrayList<InputDescriptor>()
    val dropped = ArrayList<Pair<InputDescriptor, InputDrop>>()
    val seenIds = HashSet<String>()
    val seenPorts = HashSet<Int>()
    for (d in inputs) {
        val port = knownPort(d)
        var reason = inputDropReason(d)
        if (reason == null && d.id in seenIds) reason = InputDrop.DUPLICATE_ID
        if (reason == null && port != null && port in seenPorts) reason = InputDrop.DUPLICATE_PORT
        if (reason != null) {
            dropped.add(d to reason)
        } else {
            kept.add(d)
            seenIds.add(d.id)
            if (port != null) seenPorts.add(port)
        }
    }
    return InputSelection(kept, dropped)
}

// ---------------------------------------------------------------------------------------------
// Labels
// ---------------------------------------------------------------------------------------------

private fun cleaned(label: String?): String? = label?.trim()?.takeIf { it.isNotEmpty() }

private fun genericName(type: Int): String = when (type) {
    TvInputType.HDMI -> "HDMI"
    TvInputType.COMPOSITE -> "AV"
    TvInputType.SVIDEO -> "S-Video"
    TvInputType.SCART -> "SCART"
    TvInputType.COMPONENT -> "Component"
    TvInputType.VGA -> "VGA"
    TvInputType.DVI -> "DVI"
    TvInputType.DISPLAY_PORT -> "DisplayPort"
    TvInputType.TUNER -> "Live TV"
    else -> "Input"
}

/**
 * The tile label for an input:
 *  - the user's custom label if they named the input (blank counts as unnamed);
 *  - HDMI: "HDMI <port>" when the port is known, else the framework's label, else "HDMI";
 *  - anything else: the framework's label, else a generic name for the type ("AV", "Component", "VGA",
 *    "Live TV", ...).
 */
fun inputLabel(d: InputDescriptor): String {
    cleaned(d.customLabel)?.let { return it }
    if (d.type == TvInputType.HDMI) {
        knownPort(d)?.let { return "HDMI $it" }
        return cleaned(d.systemLabel) ?: "HDMI"
    }
    return cleaned(d.systemLabel) ?: genericName(d.type)
}

// ---------------------------------------------------------------------------------------------
// Building the row
// ---------------------------------------------------------------------------------------------

val WifiTile = SystemTile(
    key = "wifi",
    label = "Wi-Fi",
    icon = SystemIconKey.WIFI,
    action = SystemTileAction.OpenSettingsPanel(SettingsPanel.WIFI),
)

val SearchTile = SystemTile(
    key = "search",
    label = "Search",
    icon = SystemIconKey.SEARCH,
    action = SystemTileAction.Search,
)

val SettingsTile = SystemTile(
    key = "settings",
    label = "Settings",
    icon = SystemIconKey.GEAR,
    action = SystemTileAction.OpenSettingsPanel(SettingsPanel.MAIN),
)

/** The tiles that are always present, always last, in this order. */
val FixedSystemTiles: List<SystemTile> = listOf(WifiTile, SearchTile, SettingsTile)

/** Position of an input type in the row: HDMI first, then the other connectors, tuner last. */
private fun connectorRank(type: Int): Int = when (type) {
    TvInputType.HDMI -> 0
    TvInputType.COMPOSITE -> 1
    TvInputType.SVIDEO -> 2
    TvInputType.SCART -> 3
    TvInputType.COMPONENT -> 4
    TvInputType.VGA -> 5
    TvInputType.DVI -> 6
    TvInputType.DISPLAY_PORT -> 7
    TvInputType.TUNER -> 9
    else -> 8 // a connector type this code does not know by name
}

private fun inputTile(d: InputDescriptor): SystemTile = SystemTile(
    key = "input:${d.id}",
    label = inputLabel(d),
    icon = SystemIconKey.PORT,
    action = if (d.type == TvInputType.TUNER) {
        SystemTileAction.TunerInput(d.id)
    } else {
        SystemTileAction.PassthroughInput(d.id)
    },
)

/**
 * The whole row, in order:
 *  1. HDMI inputs by ascending port number (unknown ports after the known ones, in the order given);
 *  2. other physical inputs: composite, S-Video, SCART, component, VGA, DVI, DisplayPort, unknown
 *     connector types, then tuner. Equal types keep the order given;
 *  3. Wi-Fi, Search, Settings ([FixedSystemTiles]).
 *
 * Which inputs count as physical, and how duplicates collapse, is [selectPhysicalInputs].
 * An empty list yields just the three fixed tiles.
 */
fun buildSystemTiles(inputs: List<InputDescriptor>): List<SystemTile> {
    val ordered = selectPhysicalInputs(inputs).kept
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<InputDescriptor>>(
                { connectorRank(it.value.type) },
                { knownPort(it.value) ?: Int.MAX_VALUE },
                { it.index },
            ),
        )
        .map { it.value }
    return ordered.map(::inputTile) + FixedSystemTiles
}

// ---------------------------------------------------------------------------------------------
// Helpers for the Android layer's port / parent detection (pure so they can be tested)
// ---------------------------------------------------------------------------------------------

private val HDMI_PORT_IN_LABEL = Regex("""hdmi\s*[-_ ]?\s*(\d{1,2})(?!\d)""", RegexOption.IGNORE_CASE)

/** "HDMI 2", "HDMI2", "hdmi-3" -> 2, 2, 3. Null when the label does not look like a numbered HDMI port. */
fun parseHdmiPort(label: String?): Int? {
    if (label == null) return null
    val match = HDMI_PORT_IN_LABEL.find(label) ?: return null
    return match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
}

/** Framework port id when it is usable (> 0), else whatever the system label says, else null. */
fun resolveHdmiPort(frameworkPort: Int?, systemLabel: String?): Int? =
    frameworkPort?.takeIf { it > 0 } ?: parseHdmiPort(systemLabel)

private val CEC_CHILD_ID = Regex("""/HDMI[0-9A-Fa-f]{6}$""")

/**
 * Fallback for when the framework will not tell us an input's parent: AOSP names CEC-device inputs
 * "<component>/HDMI<physicalAddress:4 hex><deviceId:2 hex>", while a port's own input ends in "/HW<n>".
 */
fun looksLikeCecChildId(inputId: String): Boolean = CEC_CHILD_ID.containsMatchIn(inputId)

// ---------------------------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------------------------

fun inputTypeName(type: Int): String = when (type) {
    TvInputType.OTHER -> "OTHER"
    TvInputType.TUNER -> "TUNER"
    TvInputType.COMPOSITE -> "COMPOSITE"
    TvInputType.SVIDEO -> "SVIDEO"
    TvInputType.SCART -> "SCART"
    TvInputType.COMPONENT -> "COMPONENT"
    TvInputType.VGA -> "VGA"
    TvInputType.DVI -> "DVI"
    TvInputType.HDMI -> "HDMI"
    TvInputType.DISPLAY_PORT -> "DISPLAY_PORT"
    else -> "UNKNOWN"
}

private fun describeInput(d: InputDescriptor): String =
    "id=${d.id} type=${d.type}(${inputTypeName(d.type)}) custom=${d.customLabel} system=${d.systemLabel} " +
        "port=${d.hdmiPort} parent=${d.hasParent} hidden=${d.hidden} systemApp=${d.fromSystemApp}"

/**
 * One log line per input saying whether it got a tile and why not, so the owner's logcat explains a
 * missing or extra tile. Printed by `TvInputSource` whenever the input list changes.
 */
fun describeSelection(selection: InputSelection): List<String> {
    val lines = ArrayList<String>()
    lines.add("tv inputs: ${selection.kept.size} kept, ${selection.dropped.size} dropped")
    for (d in selection.kept) lines.add("  KEEP  label='${inputLabel(d)}' ${describeInput(d)}")
    for ((d, why) in selection.dropped) lines.add("  DROP  ${why.name} (${why.explanation}) ${describeInput(d)}")
    return lines
}
