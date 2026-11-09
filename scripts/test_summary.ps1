# Prints how many tests really ran, on the JVM and on a connected device.
$build = Join-Path $PSScriptRoot '..\app\build'

function Summarize([string]$label, [string]$directory) {
    $total = 0
    $failed = 0
    $files = Get-ChildItem $directory -Recurse -Filter '*.xml' -ErrorAction SilentlyContinue
    foreach ($file in $files) {
        $xml = [xml](Get-Content $file.FullName -Raw -Encoding UTF8)
        foreach ($case in $xml.SelectNodes('//testcase')) {
            $total++
            $problem = $case.SelectSingleNode('failure|error')
            if ($problem) {
                $failed++
                "FAILED $($case.GetAttribute('classname') -replace '.*\.','').$($case.GetAttribute('name'))"
                ($problem.InnerText -split "`n" | Where-Object { $_.Trim() } | Select-Object -First 8) -join "`n"
            }
        }
    }
    "$label total=$total failed=$failed"
}

Summarize 'JVM' (Join-Path $build 'test-results\testDebugUnitTest')
Summarize 'device' (Join-Path $build 'outputs\androidTest-results\connected')
