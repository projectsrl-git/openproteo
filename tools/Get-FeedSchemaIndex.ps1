#requires -version 5.1
<#
.SYNOPSIS
    Reads the workflow definitions and the dataschema/displayschema of every feed OpenProteo loads
    and writes ONE CSV census of the record layout of the whole estate: one row per FIELD.

.DESCRIPTION
    The record layout of a feed is spread over three places that never appear together. The workflow
    XML says which SOURCE the feed belongs to and what its variables are; dataschema.json says what
    the CSV columns are; displayschema.json says what a human calls them. This tool joins the three
    and writes one file that can be filtered, sorted and read by eye.

    ONE ROW PER FIELD. Fields as columns would make the header the union of every field name in the
    estate with almost every cell empty; fields packed into one cell cannot be filtered, which is the
    only reason to write a CSV at all. Rows are in DATASCHEMA order, because that order IS the record
    layout - DisplaySequenceNr is a presentation choice and travels in its own column rather than
    being allowed to reorder the file.

    READ-ONLY. Nothing is written but the output CSV. Every input is opened with FileShare.ReadWrite
    so the tool cannot block a feed that is running while it reads.

    WHAT IS REPORTED AND NEVER RECONCILED. Three disagreements are findings about the estate, not
    noise to be smoothed over, so each is emitted in the rows AND counted in the summary:
      * a displayschema entry naming a column the dataschema does not have (an ORPHAN row, with
        in_dataschema=no);
      * a feed carrying a dataschema and no displayschema (IN, with the description columns empty);
      * dataschema.nullable and displayschema.Nullable disagreeing on the same field.
    The dataschema is the authority in all three cases, because it is the one the product acts on:
    the validate step's notNull check reads dataschema.nullable and nothing else.

    MANDATORY FIELDS. field_nullable=false means the field is mandatory. The rule mirrors
    InternalSteps exactly - mandatory ONLY when the value is the boolean false or the string "false",
    case-insensitively. Anything else, ABSENT INCLUDED, means nullable. That asymmetry is copied
    deliberately: an index that read a missing "nullable" as mandatory would declare a constraint
    that production does not enforce.

    VARIABLE COLUMNS ARE RESOLVED, AND AN UNRESOLVABLE TOKEN STAYS A TOKEN. Resolution runs against
    the same map Operations uses for feed tags (globals, then feedId/parentId/sourceId/targetId and
    the directory built-ins, then the workflow's own <variables>, which win). VarResolver resolves an
    unknown name to the empty string; here it keeps the literal ${name}, because at design time
    ${extract.rowCount} does not exist and an empty cell would be indistinguishable from "this feed
    does not define the variable". A cell therefore carries either a design-time truth or visible
    evidence that it is computed at run time, and never a lie.

    THE FOUR CLOCK BUILT-INS ARE DELIBERATELY NOT RESOLVED. Operations seeds runDate, runTs,
    currentDate and currentTs with the moment of the request, which is right for a grid refreshed
    every ten seconds and wrong for a census read weeks after it is written: the file would carry a
    date that silently stops being true the next morning. They park as tokens like any other runtime
    value. This is the one point where this tool and the Operations grid differ on purpose.

.PARAMETER WorkflowsDir
    The workflow XML directory (orchestrator.workflows-dir).

.PARAMETER BaseDir
    The feed base directory (orchestrator.default-base-dir): feeds live in <BaseDir>\<feedId>. A
    workflow carrying its own baseDir attribute overrides it, exactly as FeedLayout does.

.PARAMETER PropertiesFile
    An application.properties to take WorkflowsDir, BaseDir and the global variables from when they
    are not given explicitly.

.PARAMETER GlobalVarsFile
    The file-based global variables (default <sharedDir>\global-vars.properties). Needed only because
    variable columns are resolved: without it every ${someGlobal} degrades to a visible token. The
    summary always reports how many globals were loaded, including zero.

.PARAMETER Feed
    Optional filter, wildcards accepted, matched against both the feed id and the workflow name.

.PARAMETER Variables
    Workflow variables to add as columns, as 'name:description' or plain 'name'. One column each, in
    the order given, repeated on every row of the feed.

.PARAMETER VariableHeaders
    Description (default) writes the description as the column header, Name writes the variable name.

.PARAMETER Require
    Which feeds are indexed. Dataschema (default) takes every feed with a dataschema, so a feed with
    no displayschema is IN with the description columns empty. Both narrows it to feeds carrying both
    files. Any widens it to feeds carrying either.

.PARAMETER Out
    The CSV to write. Default .\feed-schema-index.csv

.PARAMETER Delimiter
    Field separator, default ';'.

.PARAMETER Detailed
    Add workflow_file, dataschema_path, displayschema_path and schema_source columns.

.PARAMETER NoBom
    Write UTF-8 without the byte-order mark. The default writes it, because Excel is who opens a
    census and without it the accented descriptions arrive wrong.

.EXAMPLE
    .\Get-FeedSchemaIndex.ps1 -PropertiesFile C:\tomcat\config\application.properties `
        -Variables 'recordBusinessDate:Record Business Date','originTableName:Origin table' `
        -Out D:\analisi\feed-schema-index.csv
#>
[CmdletBinding()]
param(
    [string]$WorkflowsDir,
    [string]$BaseDir,
    [string]$PropertiesFile,
    [string]$GlobalVarsFile,
    [string[]]$Feed,
    [string[]]$Variables,
    [ValidateSet('Description', 'Name')][string]$VariableHeaders = 'Description',
    [ValidateSet('Dataschema', 'Both', 'Any')][string]$Require = 'Dataschema',
    [string]$Out = 'feed-schema-index.csv',
    [string]$Delimiter = ';',
    [switch]$Detailed,
    [switch]$NoBom
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$script:LF = [string][char]10
$script:CR = [string][char]13

# ---------------------------------------------------------------------------------------------
# Reading
# ---------------------------------------------------------------------------------------------

<#
    Read a whole text file without taking a write lock on it. Feeds run while this tool reads, and a
    tool that can block a delivery to answer a question about it is not read-only in the way that
    matters.
#>
function Read-OpTextFile {
    param([string]$Path)
    $stream = $null
    $reader = $null
    try {
        $stream = New-Object System.IO.FileStream(
            $Path,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::ReadWrite)
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8, $true)
        return $reader.ReadToEnd()
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        elseif ($null -ne $stream) { $stream.Dispose() }
    }
}

<#
    A java.util.Properties-shaped file: key=value, # and ! comments, blank lines. Kept deliberately
    simple - no line continuations, no unicode escapes - and what it does not understand it skips,
    because this reads configuration to find directories, not to reproduce the JVM.
#>
function Read-OpProperties {
    param([string]$Path)
    $map = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([System.StringComparer]::Ordinal)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $map }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $map }
    $text = Read-OpTextFile -Path $Path
    foreach ($raw in ($text -split "[$script:CR$script:LF]+")) {
        $line = $raw.Trim()
        if ($line.Length -eq 0) { continue }
        if ($line.StartsWith('#') -or $line.StartsWith('!')) { continue }
        $eq = $line.IndexOf('=')
        if ($eq -lt 1) { continue }
        $key = $line.Substring(0, $eq).Trim()
        $val = $line.Substring($eq + 1).Trim()
        if ($key.Length -gt 0) { $map[$key] = $val }
    }
    return $map
}

# ---------------------------------------------------------------------------------------------
# JSON schema reading - the alias chains are the PRODUCT's, not a convenient subset
# ---------------------------------------------------------------------------------------------

<#
    Look up a property by name, CASE-SENSITIVELY, trying each alias in order.

    PowerShell's own property access ($o.name) is case-INSENSITIVE, so writing it that way would make
    this reader match ColumnName when the product would not, and report a description the application
    does not use. Hence the walk over PSObject.Properties with -ceq.
#>
function Get-JsonProp {
    param($Entry, [string[]]$Aliases)
    if ($null -eq $Entry) { return $null }
    if ($Entry -isnot [System.Management.Automation.PSCustomObject]) { return $null }
    $props = $Entry.PSObject.Properties
    foreach ($alias in $Aliases) {
        foreach ($p in $props) {
            if ($p.Name -ceq $alias) {
                if ($null -eq $p.Value) { return $null }
                return $p.Value
            }
        }
    }
    return $null
}

# The alias chains, copied from ApiController.displayNameMap and InternalSteps.readSchemaColumnNames.
$script:NameAliases = @('name', 'ColumnName', 'COLUMN_NAME')
$script:DisplayNameAliases = @('DisplayName', 'displayName', 'display_name')

<#
    Both container dialects the product accepts: a top-level array, or an object with a "columns"
    array. Anything else yields no columns rather than an error, which is what readSchemaColumnNames
    does - a schema shaped some third way is reported as having no columns, not as a crash.
#>
function Read-SchemaEntries {
    param([string]$Path)
    $text = Read-OpTextFile -Path $Path
    $root = ConvertFrom-Json -InputObject $text
    $cols = $null
    if ($root -is [System.Array]) {
        $cols = $root
    } elseif ($root -is [System.Management.Automation.PSCustomObject]) {
        foreach ($p in $root.PSObject.Properties) {
            if ($p.Name -ceq 'columns') { $cols = $p.Value; break }
        }
    }
    if ($null -eq $cols) { return @() }
    if ($cols -isnot [System.Array]) { return @() }
    return $cols
}

<#
    The column name of one entry. An entry that is a bare string IS a column name with no attributes,
    which is the third shape readSchemaColumnNames accepts.
#>
function Get-SchemaColumnName {
    param($Entry)
    if ($Entry -is [string]) { return $Entry.Trim() }
    $v = Get-JsonProp -Entry $Entry -Aliases $script:NameAliases
    if ($null -eq $v) { return $null }
    return ([string]$v).Trim()
}

<#
    Mandatory or not, by the rule InternalSteps applies:

        schemaNotNull.add(Boolean.FALSE.equals(nu) || "false".equalsIgnoreCase(String.valueOf(nu)));

    so NOT nullable only for the boolean false or the string "false". Absent, null, 0, "no", "FALSO"
    all mean nullable. Returns 'true' / 'false' as text, and '' when the entry does not carry the
    attribute at all - because "absent" and "explicitly nullable" are different facts about a schema
    and the index has room to keep them apart even though the product does not.
#>
function Get-NullableText {
    param($Entry, [string[]]$Aliases)
    if ($Entry -is [string]) { return '' }
    if ($null -eq $Entry) { return '' }
    $found = $false
    $value = $null
    if ($Entry -is [System.Management.Automation.PSCustomObject]) {
        foreach ($alias in $Aliases) {
            foreach ($p in $Entry.PSObject.Properties) {
                if ($p.Name -ceq $alias) { $found = $true; $value = $p.Value; break }
            }
            if ($found) { break }
        }
    }
    if (-not $found) { return '' }
    $notNull = $false
    if ($value -is [bool]) {
        if ($value -eq $false) { $notNull = $true }
    } else {
        $s = [string]$value
        if ($s.ToLowerInvariant() -eq 'false') { $notNull = $true }
    }
    if ($notNull) { return 'false' }
    return 'true'
}

# ---------------------------------------------------------------------------------------------
# Variable resolution
# ---------------------------------------------------------------------------------------------

$script:VarPattern = New-Object System.Text.RegularExpressions.Regex('\$\{([^${}]+)\}')
$script:MaxDepth = 12
$script:Park1 = [string][char]1
$script:Park2 = [string][char]2

<#
    Resolve ${name} against a map, innermost-first and iteratively, as VarResolver does - so a
    variable built out of another one (${TargetDestination.${targetId}}) resolves for real.

    THE ONE DIVERGENCE: an unknown name is PARKED and restored as the literal ${name} instead of
    becoming the empty string. Parking is what makes the loop terminate; without it the same
    unresolvable token would be matched again on every pass.

    ${list[N]} and ${COL@key} are not interpreted and therefore park too. Both only mean something
    against a run's published lists, and a design-time reading of them could only invent one.
#>
function Resolve-OpVars {
    param([string]$Text, $Vars)
    if ($null -eq $Text) { return '' }
    if ($Text.Length -eq 0) { return '' }
    $s = $Text
    $map = $Vars
    $evaluator = [System.Text.RegularExpressions.MatchEvaluator] {
        param($m)
        $name = $m.Groups[1].Value
        if ($map.ContainsKey($name)) { return [string]$map[$name] }
        return ($script:Park1 + $name + $script:Park2)
    }
    for ($depth = 0; $depth -lt $script:MaxDepth; $depth++) {
        $prev = $s
        $s = $script:VarPattern.Replace($s, $evaluator)
        if ($s -ceq $prev) { break }
    }
    $s = $s.Replace($script:Park1, '${').Replace($script:Park2, '}')
    return $s
}

<# True when a resolved value still carries a parked token, i.e. something could not be resolved. #>
function Test-HasUnresolved {
    param([string]$Value)
    if ([string]::IsNullOrEmpty($Value)) { return $false }
    return $script:VarPattern.IsMatch($Value)
}

# ---------------------------------------------------------------------------------------------
# CSV
# ---------------------------------------------------------------------------------------------

<#
    RFC-4180 quoting: quote only when the value contains the delimiter, a quote or a line break, and
    double an embedded quote. Export-Csv is deliberately not used - it quotes every field, and this
    file is meant to be read by eye as well as by a program.
#>
function ConvertTo-CsvField {
    param([string]$Value, [string]$Delimiter)
    if ([string]::IsNullOrEmpty($Value)) { return '' }
    $needs = $false
    if ($Value.Contains($Delimiter)) { $needs = $true }
    if ($Value.Contains('"')) { $needs = $true }
    if ($Value.Contains($script:LF)) { $needs = $true }
    if ($Value.Contains($script:CR)) { $needs = $true }
    if (-not $needs) { return $Value }
    return '"' + $Value.Replace('"', '""') + '"'
}

function ConvertTo-CsvLine {
    param([string[]]$Fields, [string]$Delimiter)
    $parts = New-Object System.Collections.Generic.List[string]
    foreach ($f in $Fields) { $parts.Add((ConvertTo-CsvField -Value $f -Delimiter $Delimiter)) }
    return [string]::Join($Delimiter, $parts.ToArray())
}

# ---------------------------------------------------------------------------------------------
# Workflow XML
# ---------------------------------------------------------------------------------------------

<#
    Read one attribute.

    Through the .NET getters, NOT the PowerShell properties. PowerShell's XmlNode adapter exposes
    attributes and child elements AS properties, so $node.Name on <workflow name="Feed A"> returns
    "Feed A" and not "workflow" - which is exactly how the root-element check first failed here.
    get_Attributes()/get_Value() cannot be shadowed by a document's own content.
#>
function Get-XmlAttr {
    param($Node, [string]$Name)
    if ($null -eq $Node) { return '' }
    $attrs = $Node.get_Attributes()
    if ($null -eq $attrs) { return '' }
    $a = $attrs.GetNamedItem($Name)
    if ($null -eq $a) { return '' }
    return [string]$a.get_Value()
}

<#
    Read one workflow definition. Attribute names and the production flag follow WorkflowXmlParser:
    production is true only for the literal "true", case-insensitively.

    Schema PARAMETERS are collected too - validate takes dataschema/displayschema and sql/json2csv
    take columnsSchema, each a free path - because the feed root is the normal place for the schema
    files but nothing forces it. See Resolve-FeedSchemaPath.
#>
function Read-WorkflowDefinition {
    param([string]$Path)
    $text = Read-OpTextFile -Path $Path
    $doc = New-Object System.Xml.XmlDocument
    $doc.XmlResolver = $null
    $doc.LoadXml($text)
    $root = $doc.get_DocumentElement()
    if ($null -eq $root) { throw 'Empty document: no root element' }
    $rootName = $root.get_Name()
    if ($rootName -cne 'workflow') {
        throw "Expected root element <workflow>, found <$rootName>"
    }

    $def = [pscustomobject]@{
        FeedId            = (Get-XmlAttr -Node $root -Name 'feedId')
        Name              = (Get-XmlAttr -Node $root -Name 'name')
        SourceId          = (Get-XmlAttr -Node $root -Name 'sourceId')
        TargetId          = (Get-XmlAttr -Node $root -Name 'targetId')
        SourceDescription = (Get-XmlAttr -Node $root -Name 'sourceDescription')
        TargetDescription = (Get-XmlAttr -Node $root -Name 'targetDescription')
        Production        = ((Get-XmlAttr -Node $root -Name 'production').ToLowerInvariant() -eq 'true')
        BaseDir           = (Get-XmlAttr -Node $root -Name 'baseDir')
        Description       = ''
        Variables         = (New-Object 'System.Collections.Generic.Dictionary[string,string]' ([System.StringComparer]::Ordinal))
        SchemaParams      = (New-Object 'System.Collections.Generic.Dictionary[string,string]' ([System.StringComparer]::Ordinal))
        File              = [System.IO.Path]::GetFileName($Path)
    }
    if ([string]::IsNullOrWhiteSpace($def.Name)) { $def.Name = $def.FeedId }

    $descNodes = $root.GetElementsByTagName('description')
    if ($descNodes.Count -gt 0) { $def.Description = ([string]$descNodes.Item(0).get_InnerText()).Trim() }

    foreach ($var in $root.SelectNodes('variables/var')) {
        $n = Get-XmlAttr -Node $var -Name 'name'
        if ($n.Length -gt 0) { $def.Variables[$n] = (Get-XmlAttr -Node $var -Name 'value') }
    }

    # step params that name a schema file: first writer wins, and the step id is kept so the summary
    # can say WHERE a non-standard path came from rather than only that one exists.
    foreach ($p in $root.SelectNodes('steps/step/param')) {
        $pn = Get-XmlAttr -Node $p -Name 'name'
        $pv = Get-XmlAttr -Node $p -Name 'value'
        if ($pv.Length -eq 0) { continue }
        $stepId = Get-XmlAttr -Node $p.get_ParentNode() -Name 'id'
        if ($pn -ceq 'dataschema' -or $pn -ceq 'columnsSchema') {
            if (-not $def.SchemaParams.ContainsKey('data')) {
                $def.SchemaParams['data'] = $pv
                $def.SchemaParams['dataStep'] = $stepId + '/' + $pn
            }
        } elseif ($pn -ceq 'displayschema') {
            if (-not $def.SchemaParams.ContainsKey('display')) {
                $def.SchemaParams['display'] = $pv
                $def.SchemaParams['displayStep'] = $stepId + '/' + $pn
            }
        }
    }
    return $def
}

# ---------------------------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------------------------

function Invoke-FeedSchemaIndex {
    param(
        [string]$WorkflowsDir,
        [string]$BaseDir,
        [string]$GlobalVarsFile,
        [string[]]$Feed,
        [string[]]$Variables,
        [string]$VariableHeaders,
        [string]$Require,
        [string]$Out,
        [string]$Delimiter,
        [bool]$Detailed,
        [bool]$NoBom
    )

    # --- variable column declarations, name:description ---
    $varDecls = New-Object System.Collections.Generic.List[object]
    $seenVarNames = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    if ($null -ne $Variables) {
        foreach ($entry in (Split-VariableSpecs -Entries $Variables)) {
            if ([string]::IsNullOrWhiteSpace($entry)) { continue }
            $colon = $entry.IndexOf(':')
            $vname = $entry
            $vdesc = ''
            if ($colon -ge 1) {
                $vname = $entry.Substring(0, $colon).Trim()
                $vdesc = $entry.Substring($colon + 1).Trim()
            } else {
                $vname = $entry.Trim()
            }
            if ($vname.Length -eq 0) { continue }
            if (-not $seenVarNames.Add($vname)) {
                throw "Duplicate variable in -Variables: '$vname'. Two columns cannot carry the same variable."
            }
            $header = $vname
            if ($VariableHeaders -eq 'Description' -and $vdesc.Length -gt 0) { $header = $vdesc }
            $varDecls.Add([pscustomobject]@{ Name = $vname; Description = $vdesc; Header = $header })
        }
    }

    # --- global variables: the lowest-precedence layer of the resolution map ---
    $globals = Read-OpProperties -Path $GlobalVarsFile
    $globalsLoaded = $globals.Count

    # --- header ---
    $header = New-Object System.Collections.Generic.List[string]
    foreach ($h in @(
        'source_id', 'source_description', 'target_id', 'target_description',
        'feed_id', 'feed_name', 'feed_description', 'production',
        'field_seq', 'field_name', 'field_type', 'field_nullable', 'display_nullable',
        'display_name', 'display_type', 'display_seq', 'viewable', 'anon_type',
        'in_dataschema', 'in_displayschema')) { $header.Add($h) }
    if ($Detailed) {
        foreach ($h in @('workflow_file', 'dataschema_path', 'displayschema_path', 'schema_source')) { $header.Add($h) }
    }
    foreach ($d in $varDecls) { $header.Add($d.Header) }

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add((ConvertTo-CsvLine -Fields $header.ToArray() -Delimiter $Delimiter))

    $counters = [ordered]@{
        feeds_seen                    = 0
        feeds_indexed                 = 0
        feeds_skipped_no_dataschema   = 0
        feeds_skipped_no_displayschema = 0
        feeds_skipped_unparseable     = 0
        feed_dir_missing              = 0
        feeds_filtered_out            = 0
        rows_written                  = 0
        displayschema_missing         = 0
        orphan_display_columns        = 0
        schema_path_conflicts         = 0
        nullable_disagreements        = 0
        variables_unresolved          = 0
        globals_loaded                = $globalsLoaded
    }
    $notes = New-Object System.Collections.Generic.List[string]
    $varDefined = New-Object 'System.Collections.Generic.Dictionary[string,int]' ([System.StringComparer]::Ordinal)
    foreach ($d in $varDecls) { $varDefined[$d.Name] = 0 }

    $notes.Add("workflows dir: $WorkflowsDir")
    $notes.Add("feed base dir: $BaseDir (a workflow's own baseDir attribute overrides it)")

    $xmlFiles = @(Get-ChildItem -LiteralPath $WorkflowsDir -Filter '*.xml' -File | Sort-Object Name)

    foreach ($xf in $xmlFiles) {
        $def = $null
        try {
            $def = Read-WorkflowDefinition -Path $xf.FullName
        } catch {
            $counters.feeds_skipped_unparseable++
            $notes.Add("unparseable workflow: $($xf.Name): $($_.Exception.Message)")
            continue
        }
        $counters.feeds_seen++

        if ($null -ne $Feed -and $Feed.Count -gt 0) {
            $match = $false
            foreach ($pat in $Feed) {
                if ($def.FeedId -like $pat) { $match = $true; break }
                if ($def.Name -like $pat) { $match = $true; break }
            }
            if (-not $match) { $counters.feeds_filtered_out++; continue }
        }

        # --- feed directory, honouring a per-workflow baseDir exactly as FeedLayout does ---
        $feedBase = $BaseDir
        if (-not [string]::IsNullOrWhiteSpace($def.BaseDir)) { $feedBase = $def.BaseDir }
        $feedDir = ''
        if (-not [string]::IsNullOrWhiteSpace($feedBase)) {
            $feedDir = Join-Path $feedBase $def.FeedId
        }

        # --- the resolution map, lowest precedence first ---
        $vars = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([System.StringComparer]::Ordinal)
        foreach ($k in $globals.Keys) { $vars[$k] = $globals[$k] }
        $vars['feedId'] = $def.FeedId
        $vars['parentId'] = (Get-ParentId -FeedId $def.FeedId)
        $vars['feedName'] = $def.Name
        $vars['sourceId'] = $def.SourceId
        $vars['targetId'] = $def.TargetId
        if ($feedDir.Length -gt 0) {
            $vars['feedDir'] = $feedDir
            $vars['landingIn'] = (Join-Path $feedDir '00_landing_in')
            $vars['landingOut'] = (Join-Path $feedDir '99_landing_out')
            $vars['logDir'] = (Join-Path $feedDir '_logs')
        }
        # workflow variables win over globals and built-ins, as they do in buildRun
        foreach ($k in $def.Variables.Keys) { $vars[$k] = $def.Variables[$k] }

        # --- locate the two schema files ---
        $dataInfo = Resolve-FeedSchemaPath -FeedDir $feedDir -FileName 'dataschema.json' `
            -ParamValue (Get-DictValue $def.SchemaParams 'data') `
            -ParamOrigin (Get-DictValue $def.SchemaParams 'dataStep') -Vars $vars
        $dispInfo = Resolve-FeedSchemaPath -FeedDir $feedDir -FileName 'displayschema.json' `
            -ParamValue (Get-DictValue $def.SchemaParams 'display') `
            -ParamOrigin (Get-DictValue $def.SchemaParams 'displayStep') -Vars $vars

        if ($dataInfo.Conflict) {
            $counters.schema_path_conflicts++
            $notes.Add("$($def.FeedId): dataschema at feed root AND at $($dataInfo.ParamOrigin) -> $($dataInfo.ParamPath); the feed root wins")
        }
        if ($dispInfo.Conflict) {
            $counters.schema_path_conflicts++
            $notes.Add("$($def.FeedId): displayschema at feed root AND at $($dispInfo.ParamOrigin) -> $($dispInfo.ParamPath); the feed root wins")
        }
        if ($dataInfo.Unresolved) { $notes.Add("$($def.FeedId): dataschema path still carries a variable: $($dataInfo.ParamPath)") }
        if ($dispInfo.Unresolved) { $notes.Add("$($def.FeedId): displayschema path still carries a variable: $($dispInfo.ParamPath)") }

        $hasData = ($dataInfo.Path.Length -gt 0)
        $hasDisp = ($dispInfo.Path.Length -gt 0)

        $keep = $false
        if ($Require -eq 'Dataschema') { $keep = $hasData }
        elseif ($Require -eq 'Both') { $keep = ($hasData -and $hasDisp) }
        else { $keep = ($hasData -or $hasDisp) }

        if (-not $keep) {
            if (-not $hasData) {
                $counters.feeds_skipped_no_dataschema++
                if (-not $dataInfo.FeedDirExists) {
                    $counters.feed_dir_missing++
                    $notes.Add("$($def.FeedId): FEED DIRECTORY NOT FOUND: $feedDir - check -BaseDir, or the workflow's own baseDir attribute")
                } else {
                    $notes.Add("$($def.FeedId): no dataschema at $($dataInfo.RootCandidate)")
                }
            } else {
                $counters.feeds_skipped_no_displayschema++
                $notes.Add("$($def.FeedId): no displayschema at $($dispInfo.RootCandidate) (excluded by -Require Both)")
            }
            continue
        }
        if (-not $hasDisp) {
            $counters.displayschema_missing++
            $notes.Add("$($def.FeedId): no displayschema at $($dispInfo.RootCandidate); description columns are empty")
        }

        # --- read the two schemas ---
        $dataEntries = @()
        $dispEntries = @()
        try {
            if ($hasData) { $dataEntries = @(Read-SchemaEntries -Path $dataInfo.Path) }
            if ($hasDisp) { $dispEntries = @(Read-SchemaEntries -Path $dispInfo.Path) }
        } catch {
            $counters.feeds_skipped_unparseable++
            $notes.Add("$($def.FeedId): unparseable schema JSON: $($_.Exception.Message)")
            continue
        }

        # displayschema indexed by column name, EXACT and case-sensitive - the product's own join
        $dispByName = New-Object 'System.Collections.Generic.Dictionary[string,object]' ([System.StringComparer]::Ordinal)
        $dispOrder = New-Object System.Collections.Generic.List[string]
        foreach ($e in $dispEntries) {
            $n = Get-SchemaColumnName -Entry $e
            if ([string]::IsNullOrEmpty($n)) { continue }
            if (-not $dispByName.ContainsKey($n)) { $dispByName[$n] = $e; $dispOrder.Add($n) }
        }

        # --- the variable columns for this feed, resolved once ---
        $varValues = New-Object System.Collections.Generic.List[string]
        foreach ($d in $varDecls) {
            $val = ''
            if ($def.Variables.ContainsKey($d.Name)) {
                $varDefined[$d.Name] = $varDefined[$d.Name] + 1
                $val = Resolve-OpVars -Text $def.Variables[$d.Name] -Vars $vars
                if (Test-HasUnresolved -Value $val) { $counters.variables_unresolved++ }
            }
            $varValues.Add($val)
        }

        $feedCommon = @(
            $def.SourceId, $def.SourceDescription, $def.TargetId, $def.TargetDescription,
            $def.FeedId, $def.Name, $def.Description, $(if ($def.Production) { 'yes' } else { 'no' })
        )
        $detailCommon = @()
        if ($Detailed) {
            $detailCommon = @($def.File, $dataInfo.Path, $dispInfo.Path, $dataInfo.Source)
        }

        # --- one row per dataschema field, in dataschema order ---
        $seq = 0
        $usedDisplay = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
        foreach ($e in $dataEntries) {
            $name = Get-SchemaColumnName -Entry $e
            if ([string]::IsNullOrEmpty($name)) { continue }
            $seq++
            $fieldType = ''
            if ($e -isnot [string]) {
                $t = Get-JsonProp -Entry $e -Aliases @('type', 'DataType', 'dataType')
                if ($null -ne $t) { $fieldType = [string]$t }
            }
            $fieldNullable = Get-NullableText -Entry $e -Aliases @('nullable', 'Nullable')

            $dName = ''; $dType = ''; $dSeq = ''; $dView = ''; $dAnon = ''; $dNullable = ''
            $inDisp = 'no'
            if ($dispByName.ContainsKey($name)) {
                $inDisp = 'yes'
                [void]$usedDisplay.Add($name)
                $de = $dispByName[$name]
                $v = Get-JsonProp -Entry $de -Aliases $script:DisplayNameAliases
                if ($null -ne $v) { $dName = ([string]$v).Trim() }
                $v = Get-JsonProp -Entry $de -Aliases @('DataType', 'dataType', 'type')
                if ($null -ne $v) { $dType = [string]$v }
                $v = Get-JsonProp -Entry $de -Aliases @('DisplaySequenceNr', 'displaySequenceNr')
                if ($null -ne $v) { $dSeq = [string]$v }
                $v = Get-JsonProp -Entry $de -Aliases @('Viewable', 'viewable')
                if ($null -ne $v) { $dView = ([string]$v).ToLowerInvariant() }
                $v = Get-JsonProp -Entry $de -Aliases @('anonType', 'AnonType', 'anon_type')
                if ($null -ne $v) { $dAnon = [string]$v }
                $dNullable = Get-NullableText -Entry $de -Aliases @('Nullable', 'nullable')

                # The two files disagreeing about a mandatory field is a finding, not something to
                # reconcile: the dataschema is what the validate step acts on, and the displayschema
                # is what a person reads. Both columns are written; the count says how often.
                if ($fieldNullable.Length -gt 0 -and $dNullable.Length -gt 0 -and $fieldNullable -cne $dNullable) {
                    $counters.nullable_disagreements++
                    $notes.Add("$($def.FeedId).$($name): nullable disagreement - dataschema=$fieldNullable displayschema=$dNullable")
                }
            }

            $row = New-Object System.Collections.Generic.List[string]
            foreach ($c in $feedCommon) { $row.Add([string]$c) }
            $row.Add([string]$seq)
            $row.Add($name)
            $row.Add($fieldType)
            $row.Add($fieldNullable)
            $row.Add($dNullable)
            $row.Add($dName)
            $row.Add($dType)
            $row.Add($dSeq)
            $row.Add($dView)
            $row.Add($dAnon)
            $row.Add('yes')
            $row.Add($inDisp)
            foreach ($c in $detailCommon) { $row.Add([string]$c) }
            foreach ($v in $varValues) { $row.Add($v) }
            $lines.Add((ConvertTo-CsvLine -Fields $row.ToArray() -Delimiter $Delimiter))
            $counters.rows_written++
        }

        # --- orphans: a displayschema entry naming a column the dataschema does not have ---
        foreach ($n in $dispOrder) {
            if ($usedDisplay.Contains($n)) { continue }
            $counters.orphan_display_columns++
            $notes.Add("$($def.FeedId).$($n): described in displayschema, absent from dataschema")
            $de = $dispByName[$n]
            $dName = ''; $dType = ''; $dSeq = ''; $dView = ''; $dAnon = ''
            $v = Get-JsonProp -Entry $de -Aliases $script:DisplayNameAliases
            if ($null -ne $v) { $dName = ([string]$v).Trim() }
            $v = Get-JsonProp -Entry $de -Aliases @('DataType', 'dataType', 'type')
            if ($null -ne $v) { $dType = [string]$v }
            $v = Get-JsonProp -Entry $de -Aliases @('DisplaySequenceNr', 'displaySequenceNr')
            if ($null -ne $v) { $dSeq = [string]$v }
            $v = Get-JsonProp -Entry $de -Aliases @('Viewable', 'viewable')
            if ($null -ne $v) { $dView = ([string]$v).ToLowerInvariant() }
            $v = Get-JsonProp -Entry $de -Aliases @('anonType', 'AnonType', 'anon_type')
            if ($null -ne $v) { $dAnon = [string]$v }
            $dNullable = Get-NullableText -Entry $de -Aliases @('Nullable', 'nullable')

            $row = New-Object System.Collections.Generic.List[string]
            foreach ($c in $feedCommon) { $row.Add([string]$c) }
            $row.Add('')          # no dataschema position: it has none
            $row.Add($n)
            $row.Add('')
            $row.Add('')
            $row.Add($dNullable)
            $row.Add($dName)
            $row.Add($dType)
            $row.Add($dSeq)
            $row.Add($dView)
            $row.Add($dAnon)
            $row.Add('no')
            $row.Add('yes')
            foreach ($c in $detailCommon) { $row.Add([string]$c) }
            foreach ($v in $varValues) { $row.Add($v) }
            $lines.Add((ConvertTo-CsvLine -Fields $row.ToArray() -Delimiter $Delimiter))
            $counters.rows_written++
        }

        $counters.feeds_indexed++
    }

    # --- write ---
    $outDir = [System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($Out))
    if (-not [string]::IsNullOrEmpty($outDir) -and -not (Test-Path -LiteralPath $outDir)) {
        [void](New-Item -ItemType Directory -Path $outDir -Force)
    }
    $encoding = New-Object System.Text.UTF8Encoding($true)
    if ($NoBom) { $encoding = New-Object System.Text.UTF8Encoding($false) }
    $eol = $script:CR + $script:LF
    $content = [string]::Join($eol, $lines.ToArray()) + $eol
    [System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($Out), $content, $encoding)

    return [pscustomobject]@{
        Counters   = $counters
        Notes      = $notes
        VarDefined = $varDefined
        OutPath    = [System.IO.Path]::GetFullPath($Out)
        Header     = $header
    }
}

<#
    A feed id ending in .v<digits> is a VERSION of the id before that suffix, mirroring
    VarResolver.parentId: ONE suffix stripped, textual and total, and a value that would strip down
    to nothing is returned unchanged rather than becoming the empty string.
#>
<#
    -Variables normally arrives as an array. It does NOT when the script is started with
    `pwsh -File`, where every argument is a plain string and a comma is just a character: three
    specifications then collapse into ONE column whose header is the rest of the command line. Found
    on the first real run, and silent - the only symptom is an absurd header nobody reads twice.

    So an entry carrying commas is split, but ONLY when the split is unambiguous: every part must
    look like a variable name optionally followed by :description, and the parts must AGREE about
    whether they carry a description. That leaves 'total:somme, moyennes' - a description that simply
    contains a comma - untouched, because its second part has no colon while the first has one.
    Splitting that would be the quiet corruption this rule exists to avoid.
#>
function Split-VariableSpecs {
    param([string[]]$Entries)
    $out = New-Object System.Collections.Generic.List[string]
    if ($null -eq $Entries) { return $out }
    $namePattern = '^\s*[A-Za-z_][A-Za-z0-9_.-]*\s*(:.*)?$'
    foreach ($entry in $Entries) {
        if ($null -eq $entry -or -not $entry.Contains(',')) { $out.Add($entry); continue }
        $parts = $entry.Split(',')
        $allValid = $true
        $withColon = 0
        foreach ($part in $parts) {
            if ($part.Trim().Length -eq 0) { $allValid = $false; break }
            if (-not [regex]::IsMatch($part, $namePattern)) { $allValid = $false; break }
            if ($part.Contains(':')) { $withColon++ }
        }
        $agree = ($withColon -eq 0 -or $withColon -eq $parts.Count)
        if ($allValid -and $agree) {
            foreach ($part in $parts) { $out.Add($part.Trim()) }
        } else {
            $out.Add($entry)
        }
    }
    return $out
}

function Get-ParentId {
    param([string]$FeedId)
    if ([string]::IsNullOrWhiteSpace($FeedId)) { return '' }
    $id = $FeedId.Trim()
    $m = [System.Text.RegularExpressions.Regex]::Match($id, '^(.+)\.v[0-9]+$')
    if (-not $m.Success) { return $id }
    $parent = $m.Groups[1].Value
    if ($parent.Length -eq 0) { return $id }
    return $parent
}

function Get-DictValue {
    param($Dict, [string]$Key)
    if ($null -eq $Dict) { return '' }
    if (-not $Dict.ContainsKey($Key)) { return '' }
    return [string]$Dict[$Key]
}

<#
    Where a feed's schema file actually is.

      1. <feedDir>\<fileName> if it exists - the normal case, where uploads are written and what
         ${feedDir}/dataschema.json resolves to in every workflow in the repository;
      2. otherwise a step parameter naming an existing file;
      3. and when BOTH exist at DIFFERENT paths, the feed root wins and the disagreement is
         REPORTED. Two schemas for one feed is a configuration question, and a report that silently
         picked one would answer it without saying so.

    A parameter path still carrying a ${...} after resolution is reported as unresolved rather than
    read as a literal directory name.
#>
function Resolve-FeedSchemaPath {
    param(
        [string]$FeedDir,
        [string]$FileName,
        [string]$ParamValue,
        [string]$ParamOrigin,
        $Vars
    )
    $rootPath = ''
    $candidate = ''
    $feedDirExists = $false
    if (-not [string]::IsNullOrWhiteSpace($FeedDir)) {
        $feedDirExists = Test-Path -LiteralPath $FeedDir -PathType Container
        $candidate = Join-Path $FeedDir $FileName
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $rootPath = $candidate }
    }

    $paramPath = ''
    $unresolved = $false
    if (-not [string]::IsNullOrWhiteSpace($ParamValue)) {
        $resolved = Resolve-OpVars -Text $ParamValue -Vars $Vars
        if (Test-HasUnresolved -Value $resolved) {
            $unresolved = $true
            $paramPath = $resolved
        } elseif (Test-Path -LiteralPath $resolved -PathType Leaf) {
            $paramPath = $resolved
        }
    }

    $conflict = $false
    $chosen = ''
    $source = ''
    if ($rootPath.Length -gt 0) {
        $chosen = $rootPath
        $source = 'feed root'
        if ($paramPath.Length -gt 0 -and -not $unresolved) {
            $a = [System.IO.Path]::GetFullPath($rootPath)
            $b = [System.IO.Path]::GetFullPath($paramPath)
            if ($a -cne $b) { $conflict = $true }
        }
    } elseif ($paramPath.Length -gt 0 -and -not $unresolved) {
        $chosen = $paramPath
        $source = $ParamOrigin
    }

    return [pscustomobject]@{
        Path          = $chosen
        Source        = $source
        ParamPath     = $paramPath
        ParamOrigin   = $ParamOrigin
        Conflict      = $conflict
        Unresolved    = $unresolved
        # What was tried, so a skip can NAME it. "no dataschema" without the path it looked for is a
        # message that cannot be acted on: it looks like a statement about the estate when it is
        # usually a statement about the base directory.
        RootCandidate = $candidate
        FeedDirExists = $feedDirExists
    }
}

# ---------------------------------------------------------------------------------------------
# Entry point. Guarded so the suite can dot-source this file and call the functions without the
# script running: an argument-less dot-source must not start an index.
# ---------------------------------------------------------------------------------------------

if ($MyInvocation.InvocationName -ne '.') {

    $props = Read-OpProperties -Path $PropertiesFile
    if ([string]::IsNullOrWhiteSpace($WorkflowsDir) -and $props.ContainsKey('orchestrator.workflows-dir')) {
        $WorkflowsDir = $props['orchestrator.workflows-dir']
    }
    if ([string]::IsNullOrWhiteSpace($BaseDir) -and $props.ContainsKey('orchestrator.default-base-dir')) {
        $BaseDir = $props['orchestrator.default-base-dir']
    }
    if ([string]::IsNullOrWhiteSpace($GlobalVarsFile)) {
        if ($props.ContainsKey('orchestrator.global-vars-file') -and -not [string]::IsNullOrWhiteSpace($props['orchestrator.global-vars-file'])) {
            $GlobalVarsFile = $props['orchestrator.global-vars-file']
        } elseif ($props.ContainsKey('orchestrator.shared-dir') -and -not [string]::IsNullOrWhiteSpace($props['orchestrator.shared-dir'])) {
            $GlobalVarsFile = Join-Path $props['orchestrator.shared-dir'] 'global-vars.properties'
        }
    }

    if ([string]::IsNullOrWhiteSpace($WorkflowsDir)) {
        throw "WorkflowsDir is required: pass -WorkflowsDir, or -PropertiesFile carrying orchestrator.workflows-dir."
    }
    if (-not (Test-Path -LiteralPath $WorkflowsDir -PathType Container)) {
        throw "WorkflowsDir not found: $WorkflowsDir"
    }
    if ([string]::IsNullOrWhiteSpace($BaseDir)) {
        throw "BaseDir is required: pass -BaseDir, or -PropertiesFile carrying orchestrator.default-base-dir. Without it there is no feed directory and therefore no schema file."
    }
    if (-not (Test-Path -LiteralPath $BaseDir -PathType Container)) {
        throw "BaseDir not found: $BaseDir"
    }
    if ($Delimiter.Length -ne 1) {
        throw "Delimiter must be exactly one character."
    }

    $result = Invoke-FeedSchemaIndex -WorkflowsDir $WorkflowsDir -BaseDir $BaseDir `
        -GlobalVarsFile $GlobalVarsFile -Feed $Feed -Variables $Variables `
        -VariableHeaders $VariableHeaders -Require $Require -Out $Out `
        -Delimiter $Delimiter -Detailed ([bool]$Detailed) -NoBom ([bool]$NoBom)

    # key=value on stdout, no colour and no prompts, so this can become an exec step unchanged.
    Write-Output ("out_file=" + $result.OutPath)
    foreach ($k in $result.Counters.Keys) {
        Write-Output ("$k=" + $result.Counters[$k])
    }
    foreach ($d in $result.VarDefined.Keys) {
        Write-Output ("variable_defined_in_feeds." + $d + "=" + $result.VarDefined[$d])
    }
    # Notes are diagnostics, not the contract: they go to the information stream so a caller parsing
    # stdout for key=value pairs is unaffected by them.
    foreach ($n in $result.Notes) { Write-Information ("note: " + $n) -InformationAction Continue }
}
