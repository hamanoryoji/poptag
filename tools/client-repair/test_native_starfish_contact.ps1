$ErrorActionPreference='Stop'
if([IntPtr]::Size -ne 4){throw 'Requires 32-bit Windows PowerShell'}
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class ShellContactTest {
 [DllImport("kernel32.dll")] public static extern IntPtr VirtualAlloc(IntPtr p,UIntPtr n,uint t,uint f);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int Contact(int player);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate void Coordinates(int direction,IntPtr start,IntPtr target,int player);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int Movement(int x,int y);
}
'@
$m=Get-Content (Join-Path $PSScriptRoot 'port-audit/starfish-contact/manifest.json') -Raw | ConvertFrom-Json
$memory=[ShellContactTest]::VirtualAlloc([IntPtr]::Zero,[UIntPtr]::new(4096),0x3000,0x40)
$base=$memory.ToInt32()
$objects=[Runtime.InteropServices.Marshal]::AllocHGlobal(4096)
[Runtime.InteropServices.Marshal]::Copy([byte[]]::new(4096),0,$objects,4096)
$actor=$objects.ToInt32()+0x100
$vtable=$objects.ToInt32()+0x500
$mode=$objects.ToInt32()+0x700
[Runtime.InteropServices.Marshal]::WriteInt32($objects,0,$mode)
[Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$actor,0,$vtable)
[Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$vtable,0x58,$base+0x180)
[byte[]]$code=for($i=0;$i -lt $m.stub.Length;$i+=2){[Convert]::ToByte($m.stub.Substring($i,2),16)}
[BitConverter]::GetBytes($objects.ToInt32()).CopyTo($code,[int]$m.globalOperand)
[BitConverter]::GetBytes([int](0x100-[int]$m.fallbackJump-5)).CopyTo($code,[int]$m.fallbackJump+1)
[Runtime.InteropServices.Marshal]::Copy($code,0,$memory,$code.Length)
[Runtime.InteropServices.Marshal]::Copy([byte[]](0xb8,9,0,0,0,0xc2,4,0),0,[IntPtr]($base+0x100),8)
[Runtime.InteropServices.Marshal]::Copy([byte[]](0x8b,0x41,8,0xc3),0,[IntPtr]($base+0x180),4)
[byte[]]$driver=0xb9,0,0,0,0,0xe9,0,0,0,0
[BitConverter]::GetBytes($actor).CopyTo($driver,1)
[BitConverter]::GetBytes([int](-0x200-10)).CopyTo($driver,6)
[Runtime.InteropServices.Marshal]::Copy($driver,0,[IntPtr]($base+0x200),10)
$contact=[Runtime.InteropServices.Marshal]::GetDelegateForFunctionPointer([IntPtr]($base+0x200),[type][ShellContactTest+Contact])
$cases=@(@(14,11,1),@(14,12,1),@(14,2,9),@(14,13,9),@(14,14,9),@(15,11,9),@(16,11,9))
foreach($case in $cases){
 [Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$mode,0x570,[int]$case[0])
 [Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$actor,8,[int]$case[1])
 if($contact.Invoke(0) -ne $case[2]){throw ('Native contact branch failed: '+($case -join ','))}
}
Write-Output 'PASS native shell contact: 7 mode/state cases; only locked monster shells use compatibility path'
# Execute the original field-copy instructions used by StarFishMon::Kick.
[byte[]]$copy=0x55,0x8b,0xec,0x53,0x56,0x57,0x8b,0xd9
for($i=0;$i -lt $m.coordinateWriter.Length;$i+=2){$copy += [Convert]::ToByte($m.coordinateWriter.Substring($i,2),16)}
$copy += [byte[]](0x5f,0x5e,0x5b,0x5d,0xc2,0x10,0)
[Runtime.InteropServices.Marshal]::Copy($copy,0,[IntPtr]($base+0x300),$copy.Length)
[BitConverter]::GetBytes([int](0x300-0x400-10)).CopyTo($driver,6)
[Runtime.InteropServices.Marshal]::Copy($driver,0,[IntPtr]($base+0x400),10)
$coordinates=[Runtime.InteropServices.Marshal]::GetDelegateForFunctionPointer([IntPtr]($base+0x400),[type][ShellContactTest+Coordinates])
$start=$objects.ToInt32()+0xe00
$target=$objects.ToInt32()+0xe10
[Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$start,0,560)
[Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$start,4,-1)
[Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$target,0,80)
[Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$target,4,-1)
$coordinates.Invoke(2,[IntPtr]$start,[IntPtr]$target,0)
foreach($pair in @(@(0x2bc,560),@(0x2c0,-1),@(0x2c4,80),@(0x2c8,-1))){
 if([Runtime.InteropServices.Marshal]::ReadInt32([IntPtr]$actor,[int]$pair[0]) -ne $pair[1]){throw 'Native shell axis reconstruction failed'}
}
Write-Output 'PASS original native shell coordinate writer: XY=(560,80), target=(-1,-1)'
[byte[]]$move=for($i=0;$i -lt $m.movementCode.Length;$i+=2){[Convert]::ToByte($m.movementCode.Substring($i,2),16)}
[Runtime.InteropServices.Marshal]::Copy($move,0,[IntPtr]($base+0x500),$move.Length)
[BitConverter]::GetBytes([int](0x500-0x600-10)).CopyTo($driver,6)
[Runtime.InteropServices.Marshal]::Copy($driver,0,[IntPtr]($base+0x600),10)
$movement=[Runtime.InteropServices.Marshal]::GetDelegateForFunctionPointer([IntPtr]($base+0x600),[type][ShellContactTest+Movement])
foreach($case in @(@(0,200,240),@(1,240,200),@(2,280,240),@(3,240,280))){
 $direction=[int]$case[0];$x=[int]$case[1];$y=[int]$case[2]
 [Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$start,0,240)
 [Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$start,4,$x)
 [Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$target,0,240)
 [Runtime.InteropServices.Marshal]::WriteInt32([IntPtr]$target,4,$y)
 $coordinates.Invoke($direction,[IntPtr]$start,[IntPtr]$target,0)
 if($movement.Invoke(240,240) -ne 1){throw ('Direction '+$direction+' stops at its starting point')}
 if($movement.Invoke($x,$y) -ne 0){throw ('Direction '+$direction+' does not stop at its destination')}
}
Write-Output 'PASS original native movement: left/up/right/down continue from origin and stop at destination (8 cases)'
