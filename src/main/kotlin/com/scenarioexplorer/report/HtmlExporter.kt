package com.scenarioexplorer.report

import com.scenarioexplorer.model.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HtmlExporter {

    fun export(
        files: List<ScenarioFile>,
        latestReports: Map<String, ReportEntry>,
        allReports: Map<String, List<ReportEntry>> = emptyMap()
    ): String {
        val allScenarios = files.flatMap { it.scenarios }
        val total = allScenarios.size
        val passed = allScenarios.count { latestReports[it.name]?.status == StepStatus.PASSED }
        val failed = allScenarios.count { latestReports[it.name]?.status == StepStatus.FAILED }
        val notRun = total - passed - failed
        val totalDur = allScenarios.sumOf { latestReports[it.name]?.duration ?: 0L }
        val ran = total - notRun
        val passRate = if (ran > 0) passed * 100.0 / ran else 0.0
        val ts = SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(Date())

        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
        sb.appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">")
        sb.appendLine("<title>Scenario Explorer Report — $ts</title>")
        sb.appendLine("<style>")
        sb.appendLine(CSS)
        sb.appendLine("</style></head><body>")

        // Header + tabs
        sb.appendLine("<div class=\"header\">")
        sb.appendLine("<h1>📊 Scenario Explorer Report</h1>")
        sb.appendLine("<span class=\"ts\">$ts</span>")
        sb.appendLine("</div>")
        sb.appendLine("<div class=\"tabs\">")
        sb.appendLine("<button class=\"tab active\" onclick=\"switchTab('dashboard',this)\">Dashboard</button>")
        sb.appendLine("<button class=\"tab\" onclick=\"switchTab('scenarios',this)\">Senaryolar</button>")
        sb.appendLine("</div>")

        // ===== PAGE 1: DASHBOARD =====
        sb.appendLine("<div id=\"dashboard\" class=\"page active\">")
        buildDashboard(sb, total, passed, failed, notRun, totalDur, passRate, ran, files, allScenarios, latestReports)
        sb.appendLine("</div>")

        // ===== PAGE 2: SCENARIOS =====
        sb.appendLine("<div id=\"scenarios\" class=\"page\">")
        buildScenarios(sb, files, latestReports)
        sb.appendLine("</div>")

        sb.appendLine("<script>")
        sb.appendLine(JS)
        sb.appendLine("</script>")
        sb.appendLine("</body></html>")
        return sb.toString()
    }

    private fun buildDashboard(
        sb: StringBuilder, total: Int, passed: Int, failed: Int, notRun: Int,
        totalDur: Long, passRate: Double, ran: Int,
        files: List<ScenarioFile>, allScenarios: List<Scenario>, reports: Map<String, ReportEntry>
    ) {
        // Cards
        sb.appendLine("<div class=\"cards\">")
        sb.appendLine(card("Toplam", "$total", "blue"))
        sb.appendLine(card("Passed", "$passed", "green"))
        sb.appendLine(card("Failed", "$failed", "red"))
        sb.appendLine(card("Not Run", "$notRun", "gray"))
        sb.appendLine(card("Süre", fmtDur(totalDur), "blue"))
        sb.appendLine("</div>")

        // Pass rate
        if (ran > 0) {
            val pw = fmt(passRate)
            val fw = fmt(100.0 - passRate)
            sb.appendLine("<div class=\"section\"><h2>Başarı Oranı</h2>")
            sb.appendLine("<div class=\"bar-track\"><div class=\"bar-pass\" style=\"width:${pw}%\"></div><div class=\"bar-fail\" style=\"width:${fw}%\"></div></div>")
            sb.appendLine("<div class=\"bar-label\">${pw}% ($ran koşuldu)</div></div>")
        }

        // Feature table
        sb.appendLine("<div class=\"section\"><h2>Feature Bazlı Dağılım</h2>")
        sb.appendLine("<table><thead><tr><th>Feature</th><th>Toplam</th><th>✓</th><th>✗</th><th>Oran</th><th>Süre</th></tr></thead><tbody>")
        for (sf in files) {
            val sc = sf.scenarios; val t = sc.size
            val p = sc.count { reports[it.name]?.status == StepStatus.PASSED }
            val f = sc.count { reports[it.name]?.status == StepStatus.FAILED }
            val d = sc.sumOf { reports[it.name]?.duration ?: 0L }
            val rate = if (p + f > 0) "%.0f%%".format(p * 100.0 / (p + f)) else "-"
            sb.appendLine("<tr><td>${esc(sf.featureName)}</td><td>$t</td><td class=\"g\">$p</td><td class=\"r\">$f</td><td>$rate</td><td class=\"dim\">${fmtDur(d)}</td></tr>")
        }
        sb.appendLine("</tbody></table></div>")

        // Tag table
        val tagMap = mutableMapOf<String, MutableList<String>>()
        for (s in allScenarios) for (tag in s.tags) tagMap.getOrPut(tag) { mutableListOf() }.add(s.name)
        if (tagMap.isNotEmpty()) {
            sb.appendLine("<div class=\"section\"><h2>Tag Bazlı Dağılım</h2>")
            sb.appendLine("<table><thead><tr><th>Tag</th><th>Toplam</th><th>✓</th><th>✗</th><th>Oran</th></tr></thead><tbody>")
            for ((tag, names) in tagMap.entries.sortedByDescending { it.value.size }) {
                val t = names.size
                val p = names.count { reports[it]?.status == StepStatus.PASSED }
                val f = names.count { reports[it]?.status == StepStatus.FAILED }
                val rate = if (p + f > 0) "%.0f%%".format(p * 100.0 / (p + f)) else "-"
                sb.appendLine("<tr><td><span class=\"tag\">${esc(tag)}</span></td><td>$t</td><td class=\"g\">$p</td><td class=\"r\">$f</td><td>$rate</td></tr>")
            }
            sb.appendLine("</tbody></table></div>")
        }
    }

    private fun buildScenarios(sb: StringBuilder, files: List<ScenarioFile>, reports: Map<String, ReportEntry>) {
        sb.appendLine("<div class=\"split\">")

        // === LEFT: Tree ===
        sb.appendLine("<div class=\"split-left\">")
        sb.appendLine("<input type=\"text\" id=\"sf\" placeholder=\"Senaryo ara...\" onkeyup=\"filterScenarios()\">")

        var scenarioIdx = 0
        val grouped = files.groupBy { it.file.parentFile?.path ?: "" }
        for ((dirPath, scenarioFiles) in grouped.toSortedMap()) {
            val dirName = java.io.File(dirPath).name.ifEmpty { dirPath }
            val dirTotal = scenarioFiles.sumOf { it.scenarios.size }
            val dirP = scenarioFiles.sumOf { sf -> sf.scenarios.count { reports[it.name]?.status == StepStatus.PASSED } }
            val dirF = scenarioFiles.sumOf { sf -> sf.scenarios.count { reports[it.name]?.status == StepStatus.FAILED } }

            sb.appendLine("<div class=\"tree-dir\">")
            sb.appendLine("<div class=\"node dir-node\" onclick=\"toggle(this)\"><span class=\"arrow\">▶</span> 📁 <span class=\"node-name\">${esc(dirName)}</span> <span class=\"stats\">$dirTotal | <span class=\"g\">✓$dirP</span> <span class=\"r\">✗$dirF</span></span></div>")
            sb.appendLine("<div class=\"children\" style=\"display:none\">")

            for (sf in scenarioFiles) {
                val ft = sf.scenarios.size
                val fp = sf.scenarios.count { reports[it.name]?.status == StepStatus.PASSED }
                val ff = sf.scenarios.count { reports[it.name]?.status == StepStatus.FAILED }
                val fd = sf.scenarios.sumOf { reports[it.name]?.duration ?: 0L }

                sb.appendLine("<div class=\"tree-file\">")
                sb.appendLine("<div class=\"node file-node\" onclick=\"toggle(this)\"><span class=\"arrow\">▶</span> 📄 <span class=\"node-name\">${esc(sf.featureName)}</span>")
                sb.appendLine(" <span class=\"stats\">$ft | <span class=\"g\">✓$fp</span> <span class=\"r\">✗$ff</span> | ${fmtDur(fd)}</span></div>")
                sb.appendLine("<div class=\"children\" style=\"display:none\">")

                for (scenario in sf.scenarios) {
                    val report = reports[scenario.name]
                    val status = report?.status ?: StepStatus.NOT_RUN
                    val sCls = status.name.lowercase()
                    val sIcon = statusIcon(status)
                    val dur = report?.duration ?: 0L
                    val sid = "sc_$scenarioIdx"

                    sb.appendLine("<div class=\"scenario\" data-name=\"${esc(scenario.name).lowercase()}\">")
                    sb.appendLine("<div class=\"node sc-node\" onclick=\"selectScenario('$sid',this)\">")
                    sb.appendLine("<span class=\"badge $sCls\">$sIcon</span> <span class=\"sc-name\">${esc(scenario.name)}</span>")
                    sb.appendLine("<span class=\"dim sc-dur\">${fmtDur(dur)}</span></div>")
                    sb.appendLine("</div>")

                    scenarioIdx++
                }
                sb.appendLine("</div></div>") // children, tree-file
            }
            sb.appendLine("</div></div>") // children, tree-dir
        }
        sb.appendLine("</div>") // split-left

        // === RIGHT: Detail panels ===
        sb.appendLine("<div class=\"split-right\">")
        sb.appendLine("<div id=\"detail-empty\" class=\"detail-empty\"><div>📋<br>Bir senaryo seçin</div></div>")

        scenarioIdx = 0
        for ((_, scenarioFiles) in grouped.toSortedMap()) {
            for (sf in scenarioFiles) {
                for (scenario in sf.scenarios) {
                    val report = reports[scenario.name]
                    val status = report?.status ?: StepStatus.NOT_RUN
                    val sCls = status.name.lowercase()
                    val dur = report?.duration ?: 0L
                    val sid = "sc_$scenarioIdx"

                    sb.appendLine("<div id=\"$sid\" class=\"detail-panel\" style=\"display:none\">")

                    // Header
                    sb.appendLine("<div class=\"detail-header\">")
                    sb.appendLine("<span class=\"badge $sCls\">${statusIcon(status)} ${status.name}</span>")
                    sb.appendLine("<h3>${esc(scenario.name)}</h3>")
                    if (scenario.tags.isNotEmpty()) {
                        sb.appendLine("<div class=\"detail-tags\">")
                        scenario.tags.forEach { sb.append("<span class=\"tag\">${esc(it)}</span> ") }
                        sb.appendLine("</div>")
                    }
                    sb.appendLine("<div class=\"detail-meta\">")
                    sb.appendLine("<span>📄 ${esc(sf.featureName)}</span>")
                    sb.appendLine("<span>⏱ ${fmtDur(dur)}</span>")
                    val totalSteps = scenario.steps.size
                    val passedSteps = (report?.steps ?: emptyList()).count { it.status == StepStatus.PASSED }
                    val failedSteps = (report?.steps ?: emptyList()).count { it.status == StepStatus.FAILED }
                    sb.appendLine("<span>Steps: $totalSteps (<span class=\"g\">✓$passedSteps</span> <span class=\"r\">✗$failedSteps</span>)</span>")
                    sb.appendLine("</div></div>")

                    // Steps
                    sb.appendLine("<div class=\"detail-steps\">")
                    val reportSteps = report?.steps ?: emptyList()
                    for ((idx, step) in scenario.steps.withIndex()) {
                        val rs = reportSteps.getOrNull(idx)
                        val stStatus = rs?.status ?: step.status
                        val stDur = rs?.duration ?: step.duration
                        val stError = rs?.errorMessage ?: step.errorMessage
                        val stImg = rs?.screenshotBase64 ?: step.screenshotBase64

                        sb.appendLine("<div class=\"step\">")
                        sb.appendLine("<span class=\"badge sm ${stStatus.name.lowercase()}\">${statusIcon(stStatus)}</span>")
                        sb.appendLine("<span class=\"kw\">${esc(step.keyword)}</span> ${esc(step.text)}")
                        if (stDur != null && stDur > 0) sb.appendLine(" <span class=\"dim step-dur\">${fmtMs(stDur)}</span>")
                        sb.appendLine("</div>")

                        if (step.dataTable != null) {
                            sb.appendLine("<div class=\"dt\"><table class=\"dt-table\"><thead><tr>")
                            step.dataTable.headers.forEach { sb.appendLine("<th>${esc(it)}</th>") }
                            sb.appendLine("</tr></thead><tbody>")
                            step.dataTable.rows.forEach { row -> sb.appendLine("<tr>"); row.forEach { sb.appendLine("<td>${esc(it)}</td>") }; sb.appendLine("</tr>") }
                            sb.appendLine("</tbody></table></div>")
                        }

                        if (stStatus == StepStatus.FAILED && stError != null) {
                            sb.appendLine("<pre class=\"error\">${esc(stError)}</pre>")
                        }

                        if (stImg != null) {
                            val clean = stImg.replace("\\s".toRegex(), "")
                            val imgId = "img_${scenarioIdx}_${idx}"
                            sb.appendLine("<div class=\"img-toggle\"><button onclick=\"toggleImg('$imgId')\">📷 Screenshot</button></div>")
                            sb.appendLine("<div id=\"$imgId\" class=\"screenshot\" style=\"display:none\"><img src=\"data:image/png;base64,$clean\" loading=\"lazy\"></div>")
                        }
                    }

                    // Extra hook steps
                    if (reportSteps.size > scenario.steps.size) {
                        for (i in scenario.steps.size until reportSteps.size) {
                            val extra = reportSteps[i]
                            sb.appendLine("<div class=\"step\"><span class=\"badge sm ${extra.status.name.lowercase()}\">${statusIcon(extra.status)}</span> <span class=\"kw\">Hook</span> ${esc(extra.text)}")
                            if (extra.duration != null && extra.duration > 0) sb.append(" <span class=\"dim step-dur\">${fmtMs(extra.duration)}</span>")
                            sb.appendLine("</div>")
                            if (extra.status == StepStatus.FAILED && extra.errorMessage != null) sb.appendLine("<pre class=\"error\">${esc(extra.errorMessage)}</pre>")
                            if (extra.screenshotBase64 != null) {
                                val clean = extra.screenshotBase64.replace("\\s".toRegex(), "")
                                val imgId = "img_hook_${scenarioIdx}_${i}"
                                sb.appendLine("<div class=\"img-toggle\"><button onclick=\"toggleImg('$imgId')\">📷 Screenshot</button></div>")
                                sb.appendLine("<div id=\"$imgId\" class=\"screenshot\" style=\"display:none\"><img src=\"data:image/png;base64,$clean\" loading=\"lazy\"></div>")
                            }
                        }
                    }

                    sb.appendLine("</div>") // detail-steps
                    sb.appendLine("</div>") // detail-panel

                    scenarioIdx++
                }
            }
        }
        sb.appendLine("</div>") // split-right
        sb.appendLine("</div>") // split
    }

    // --- Helpers ---
    private fun card(label: String, value: String, color: String) =
        "<div class=\"card $color\"><div class=\"cv\">$value</div><div class=\"cl\">$label</div></div>"
    private fun statusIcon(s: StepStatus) = when (s) { StepStatus.PASSED -> "✓"; StepStatus.FAILED -> "✗"; else -> "○" }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun fmt(d: Double) = String.format(Locale.US, "%.1f", d)
    private fun fmtDur(ms: Long): String {
        if (ms == 0L) return "-"
        val h = ms / 3_600_000; val m = (ms % 3_600_000) / 60_000; val s = (ms % 60_000) / 1000
        return "%02dh %02dm %02ds".format(h, m, s)
    }
    private fun fmtMs(ms: Long) = when { ms < 1000 -> "${ms}ms"; ms < 60_000 -> "%.1fs".format(ms / 1000.0); else -> "%dm %ds".format(ms / 60_000, (ms % 60_000) / 1000) }

    private val JS = """
function switchTab(id,btn){
  document.querySelectorAll('.page').forEach(p=>p.classList.remove('active'));
  document.querySelectorAll('.tab').forEach(t=>t.classList.remove('active'));
  document.getElementById(id).classList.add('active');
  btn.classList.add('active');
}
function toggle(el){
  var ch=el.nextElementSibling;if(!ch)return;
  var a=el.querySelector('.arrow');
  if(ch.style.display==='none'){ch.style.display='';if(a)a.textContent='▼'}
  else{ch.style.display='none';if(a)a.textContent='▶'}
}
function toggleImg(id){
  var el=document.getElementById(id);
  el.style.display=el.style.display==='none'?'':'none';
}
function filterScenarios(){
  var q=document.getElementById('sf').value.toLowerCase();
  document.querySelectorAll('.scenario').forEach(function(s){
    s.style.display=s.getAttribute('data-name').indexOf(q)>-1?'':'none';
  });
}
var _prevNode=null;
function selectScenario(id,nodeEl){
  // Hide all detail panels
  document.querySelectorAll('.detail-panel').forEach(p=>p.style.display='none');
  document.getElementById('detail-empty').style.display='none';
  // Show selected
  var panel=document.getElementById(id);
  if(panel)panel.style.display='';
  // Highlight selected node
  if(_prevNode)_prevNode.classList.remove('selected');
  nodeEl.classList.add('selected');
  _prevNode=nodeEl;
}
    """.trimIndent()

    private val CSS = """
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0d1117;color:#c9d1d9;padding:0;line-height:1.6}
.header{display:flex;justify-content:space-between;align-items:center;padding:20px 24px 12px;border-bottom:1px solid #21262d}
.header h1{font-size:20px;font-weight:600} .ts{color:#8b949e;font-size:12px}
.tabs{display:flex;gap:0;border-bottom:2px solid #21262d;padding:0 24px;background:#161b22}
.tab{padding:10px 24px;border:none;background:none;color:#8b949e;font-size:14px;font-weight:500;cursor:pointer;border-bottom:2px solid transparent;margin-bottom:-2px;transition:all .2s}
.tab:hover{color:#c9d1d9} .tab.active{color:#c9d1d9;border-bottom-color:#42a5f5}
.page{display:none;padding:24px} .page.active{display:block}
.split{display:flex;gap:0;height:calc(100vh - 130px)}
.split-left{width:38%;min-width:280px;overflow-y:auto;border-right:1px solid #21262d;padding:12px 12px 12px 0}
.split-right{flex:1;overflow-y:auto;padding:0 0 0 16px}
.detail-empty{display:flex;align-items:center;justify-content:center;height:100%;color:#8b949e;font-size:16px;text-align:center}
.detail-panel{}
.detail-header{padding:16px 0 12px;border-bottom:1px solid #21262d;margin-bottom:12px}
.detail-header h3{font-size:16px;margin:8px 0 6px}
.detail-tags{margin-bottom:6px}
.detail-meta{display:flex;gap:16px;font-size:12px;color:#8b949e;flex-wrap:wrap}
.detail-steps{padding:4px 0}
.sc-node{padding:5px 8px;border-radius:4px;font-size:12px}
.sc-node.selected{background:rgba(66,165,245,.15);border-left:2px solid #42a5f5}
.sc-name{overflow:hidden;text-overflow:ellipsis;flex:1;min-width:0}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:12px;margin-bottom:24px}
.card{background:#161b22;border:1px solid #21262d;border-radius:10px;padding:14px;text-align:center;border-top:3px solid #30363d}
.card.green{border-top-color:#4caf50} .card.red{border-top-color:#ef5350} .card.yellow{border-top-color:#ffb300} .card.blue{border-top-color:#42a5f5} .card.gray{border-top-color:#9e9e9e}
.cv{font-size:24px;font-weight:700}
.card.green .cv{color:#4caf50} .card.red .cv{color:#ef5350} .card.yellow .cv{color:#ffb300} .card.blue .cv{color:#42a5f5} .card.gray .cv{color:#9e9e9e}
.cl{font-size:11px;color:#8b949e;margin-top:4px}
.section{margin-bottom:28px} .section h2{font-size:15px;font-weight:600;margin-bottom:12px;padding-bottom:6px;border-bottom:1px solid #21262d}
.bar-track{border-radius:8px;height:22px;overflow:hidden;margin-bottom:6px;display:flex;background:rgba(255,255,255,.04)}
.bar-pass{height:100%;background:linear-gradient(90deg,#4caf50,#81c784)} .bar-fail{height:100%;background:rgba(239,83,80,.6)}
.bar-label{font-size:13px;color:#8b949e}
table{width:100%;border-collapse:collapse;font-size:13px;background:#161b22;border-radius:8px;overflow:hidden;margin-bottom:8px}
thead th{background:#1c2128;padding:10px 12px;text-align:left;font-weight:600;border-bottom:2px solid #21262d}
tbody td{padding:8px 12px;border-bottom:1px solid #21262d} tbody tr:hover{background:#1c2128}
.g{color:#4caf50} .r{color:#ef5350} .y{color:#ffb300} .dim{color:#8b949e}
.tag{display:inline-block;background:#1c2128;border:1px solid #30363d;border-radius:12px;padding:1px 8px;font-size:11px;margin:1px 2px}
.badge{display:inline-block;padding:2px 8px;border-radius:10px;font-size:12px;font-weight:600}
.badge.passed{background:rgba(76,175,80,.15);color:#4caf50} .badge.failed{background:rgba(239,83,80,.15);color:#ef5350}
.badge.skipped{background:rgba(255,179,0,.15);color:#ffb300} .badge.not_run,.badge.pending,.badge.undefined{background:rgba(158,158,158,.15);color:#9e9e9e}
.badge.sm{padding:1px 6px;font-size:11px;border-radius:8px}
#sf{width:100%;padding:10px 14px;margin-bottom:16px;border-radius:8px;border:1px solid #30363d;background:#161b22;color:#c9d1d9;font-size:13px;outline:none}
#sf:focus{border-color:#42a5f5}
.tree-dir{margin-bottom:6px} .tree-file{margin-bottom:4px}
.node{cursor:pointer;padding:8px 10px;border-radius:6px;font-size:13px;display:flex;align-items:center;gap:6px;user-select:none;overflow:hidden;white-space:nowrap}
.node:hover{background:rgba(255,255,255,.04)}
.node-name{font-weight:600;overflow:hidden;text-overflow:ellipsis;flex-shrink:1;min-width:0}
.children{padding-left:18px}
.scenario{border-left:3px solid #30363d;border-radius:4px;margin-bottom:2px}
.scenario:has(.badge.passed){border-left-color:#4caf50}
.scenario:has(.badge.failed){border-left-color:#ef5350}
.scenario:has(.badge.skipped){border-left-color:#ffb300}
.sc-dur{margin-left:auto;flex-shrink:0;font-size:11px} .sc-body{padding:6px 8px 10px 16px}
.step{padding:5px 0;font-size:12px;display:flex;align-items:center;gap:6px;flex-wrap:wrap;border-bottom:1px solid rgba(255,255,255,.04)}
.step:last-child{border-bottom:none} .step-dur{margin-left:auto;font-size:11px}
.kw{color:#42a5f5;font-weight:600}
.dt{padding:4px 0 4px 28px} .dt-table{font-size:12px;width:auto} .dt-table th,.dt-table td{padding:4px 10px}
pre.error{background:rgba(239,83,80,.08);border:1px solid rgba(239,83,80,.2);border-radius:6px;padding:8px 12px;font-size:11px;color:#ef5350;white-space:pre-wrap;word-break:break-all;max-height:150px;overflow:auto;margin:4px 0 4px 28px}
.img-toggle{margin:4px 0 2px 28px} .img-toggle button{background:#161b22;border:1px solid #30363d;color:#c9d1d9;padding:4px 12px;border-radius:6px;font-size:12px;cursor:pointer}
.img-toggle button:hover{background:#1c2128;border-color:#42a5f5}
.screenshot{margin:4px 0 8px 28px} .screenshot img{max-width:600px;border-radius:6px;border:1px solid #21262d}
.arrow{font-size:10px;color:#8b949e;min-width:12px;display:inline-block;flex-shrink:0}
.stats{margin-left:auto;font-size:11px;color:#8b949e;white-space:nowrap;flex-shrink:0}
@media(prefers-color-scheme:light){
body{background:#fff;color:#24292f}
.header{border-bottom-color:#d0d7de} .tabs{background:#f6f8fa;border-bottom-color:#d0d7de}
.tab{color:#656d76} .tab:hover{color:#24292f} .tab.active{color:#24292f;border-bottom-color:#42a5f5}
.card{background:#f6f8fa;border-color:#d0d7de} table{background:#fff}
thead th{background:#f6f8fa;border-bottom-color:#d0d7de} tbody td{border-bottom-color:#d0d7de} tbody tr:hover{background:#f6f8fa}
.tag{background:#f6f8fa;border-color:#d0d7de} .dim,.ts,.cl,.bar-label,.arrow,.stats{color:#656d76}
.section h2{border-bottom-color:#d0d7de} .bar-track{background:rgba(0,0,0,.06)}
#sf{background:#f6f8fa;border-color:#d0d7de;color:#24292f}
.node:hover{background:rgba(0,0,0,.03)} .step{border-bottom-color:rgba(0,0,0,.06)}
.split-left{border-right-color:#d0d7de} .detail-header{border-bottom-color:#d0d7de}
.sc-node.selected{background:rgba(66,165,245,.1)}
.scenario{border-left-color:#d0d7de} pre.error{background:rgba(239,83,80,.05)}
.screenshot img{border-color:#d0d7de}
.img-toggle button{background:#f6f8fa;border-color:#d0d7de;color:#24292f}
.img-toggle button:hover{background:#eaeef2}
}
    """.trimIndent()
}
