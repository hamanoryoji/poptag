$ErrorActionPreference='Stop'
if ([IntPtr]::Size -ne 4) { throw 'Requires 32-bit Windows PowerShell' }
Add-Type @'
using System;using System.Runtime.InteropServices;
public static class NativeBoundsTest {
 [DllImport("kernel32.dll")] public static extern IntPtr VirtualAlloc(IntPtr p,UIntPtr n,uint t,uint f);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int Check(int row,int column);
}
'@
$memory=[NativeBoundsTest]::VirtualAlloc([IntPtr]::Zero,[UIntPtr]::new(4096),0x3000,0x40)
$address=$memory.ToInt32()
$world=[Runtime.InteropServices.Marshal]::AllocHGlobal(0x1a0000)
[Runtime.InteropServices.Marshal]::WriteInt32($world,0x19a120,13)
[Runtime.InteropServices.Marshal]::WriteInt32($world,0x19a114,15)
$manifest=Get-Content (Join-Path $PSScriptRoot 'port-audit/crash-probe/native-bounds-check.json') -Raw | ConvertFrom-Json
[byte[]]$code=for($i=0;$i -lt $manifest.code.Length;$i+=2){[Convert]::ToByte($manifest.code.Substring($i,2),16)}
foreach($call in $manifest.calls){[BitConverter]::GetBytes([int](0x100-[int]$call.end)).CopyTo($code,[int]$call.offset)}
[Runtime.InteropServices.Marshal]::Copy($code,0,$memory,$code.Length)
[Runtime.InteropServices.Marshal]::Copy([byte[]](0x8b,0x01,0xc3),0,[IntPtr]($address+0x100),3)
[byte[]]$driver=0xb9,0,0,0,0,0xe9,0,0,0,0
[BitConverter]::GetBytes($world.ToInt32()).CopyTo($driver,1)
[BitConverter]::GetBytes([int](-0x200-$driver.Length)).CopyTo($driver,6)
[Runtime.InteropServices.Marshal]::Copy($driver,0,[IntPtr]($address+0x200),$driver.Length)
$check=[Runtime.InteropServices.Marshal]::GetDelegateForFunctionPointer([IntPtr]($address+0x200),[type][NativeBoundsTest+Check])
if ($check.Invoke(13,5) -ne 0 -or $check.Invoke(12,5) -ne 1) {throw 'Captured fault coordinate did not reproduce native rejection'}
$accepted=0;$rejected=0
foreach($x in 5..11){foreach($y in 7..13){
 if([Math]::Abs($x-8) -ne 3 -and [Math]::Abs($y-10) -ne 3){continue}
 if($check.Invoke($y,$x) -eq 1){$accepted++}else{$rejected++;if($y -ne 13){throw 'Unexpected rejected tile'}}
}}
if($accepted -ne 17 -or $rejected -ne 7){throw 'Native bounds result differs from captured attack regression'}
Write-Output 'PASS original native tile bounds: captured (row 13, col 5) rejected; all 17 retained attack cells accepted; all 7 removed cells rejected'
