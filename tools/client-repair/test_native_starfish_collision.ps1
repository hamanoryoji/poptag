$ErrorActionPreference='Stop'
if([IntPtr]::Size -ne 4){throw 'Requires 32-bit Windows PowerShell'}
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class ShellBoundsTest {
 [DllImport("kernel32.dll")] public static extern IntPtr VirtualAlloc(IntPtr p,UIntPtr n,uint t,uint f);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate IntPtr Bounds(IntPtr rectangle);
}
'@
$manifest=Get-Content (Join-Path $PSScriptRoot 'port-audit/starfish-collision/native-bounds.json') -Raw | ConvertFrom-Json
$memory=[ShellBoundsTest]::VirtualAlloc([IntPtr]::Zero,[UIntPtr]::new(4096),0x3000,0x40)
$base=$memory.ToInt32()
[byte[]]$code=for($i=0;$i -lt $manifest.code.Length;$i+=2){[Convert]::ToByte($manifest.code.Substring($i,2),16)}
[Runtime.InteropServices.Marshal]::Copy($code,0,$memory,$code.Length)
[Runtime.InteropServices.Marshal]::Copy([byte[]](0x8b,0x41,0x38,0xc3),0,[IntPtr]($base+0x100),4)
[Runtime.InteropServices.Marshal]::Copy([byte[]](0x8b,0x41,0x44,0xc3),0,[IntPtr]($base+0x110),4)
$data=[Runtime.InteropServices.Marshal]::AllocHGlobal(4096)
[Runtime.InteropServices.Marshal]::Copy([byte[]]::new(4096),0,$data,4096)
$actor=$data.ToInt32()
$vtable=$actor+0x400
$rectangle=$actor+0x600
[Runtime.InteropServices.Marshal]::WriteInt32($data,0,$vtable)
[Runtime.InteropServices.Marshal]::WriteInt32($data,0x38,200)
[Runtime.InteropServices.Marshal]::WriteInt32($data,0x44,119)
[Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$vtable,0x68,$base+0x100)
[Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$vtable,0x6c,$base+0x110)
[byte[]]$driver=0xb9,0,0,0,0,0xe9,0,0,0,0
[BitConverter]::GetBytes($actor).CopyTo($driver,1)
[BitConverter]::GetBytes([int](-0x200-10)).CopyTo($driver,6)
[Runtime.InteropServices.Marshal]::Copy($driver,0,[IntPtr]($base+0x200),10)
$bounds=[Runtime.InteropServices.Marshal]::GetDelegateForFunctionPointer([IntPtr]($base+0x200),[type][ShellBoundsTest+Bounds])
function Apply-Reply([string]$hex){
 [byte[]]$b=for($i=0;$i -lt $hex.Length;$i+=2){[Convert]::ToByte($hex.Substring($i,2),16)}
 $event=[int]$b[4] -bxor 0x5a
 $a=(([int]$b[5] -shl 8) -bor [int]$b[6]) -bxor 0xa569
 $c=(([int]$b[7] -shl 8) -bor [int]$b[8]) -bxor 0xa569
 $offset=if($event -eq 11){0x110}elseif($event -eq 12){0x108}else{throw 'Unexpected native event'}
 [Runtime.InteropServices.Marshal]::WriteInt32($data,$offset,$a)
 [Runtime.InteropServices.Marshal]::WriteInt32($data,$offset+4,$c)
}
function Read-Rectangle {
 $null=$bounds.Invoke([IntPtr]$rectangle)
 return @(0,4,8,12 | ForEach-Object {[Runtime.InteropServices.Marshal]::ReadInt32([IntPtr]$rectangle,$_ )})
}
Apply-Reply 'a567a56b56a56ba56a'
Apply-Reply 'a567a56b51a54aa548'
$bad=Read-Rectangle
if(($bad -join ',') -ne '235,83,2,3'){throw 'Old swapped collision fields did not reproduce the tiny displaced hitbox'}
Apply-Reply 'a567a56b51a56ba56a'
Apply-Reply 'a567a56b56a54aa548'
$good=Read-Rectangle
if(($good -join ',') -ne '202,83,35,33'){throw 'Correct replies did not restore the original collision rectangle'}
$touches=@(@(183,94,20,20),@(236,94,20,20),@(212,64,20,20),@(212,115,20,20))
foreach($p in $touches){
 if(-not($p[0] -lt $good[0]+$good[2] -and $p[0]+$p[2] -gt $good[0] -and $p[1] -lt $good[1]+$good[3] -and $p[1]+$p[3] -gt $good[1])){throw 'One approach direction misses the restored shell rectangle'}
}
Write-Output 'PASS original native collision getter: reproduced bad 2x3 rectangle, restored 35x33; four approach rectangles intersect'
