-- Tarkov tactical devices: modes read from the vanilla prefabs (mode_000 is
-- off), per-part state in modData, one native PZ torch per weapon.
-- Kinds: light (white torch), laser (visible), ir (IR laser), irlight (IR
-- illuminator). IR is visible only through night vision of the viewer.
local C=require('TW/Core')
local D=C.data
local T={nvg=setmetatable({},{__mode='k'})}
TWTactical=T

function T.modes(tpl)
    local item=D.items[tpl]
    return item and item.modes
end

function T.isDevice(tpl)
    local modes=T.modes(tpl)
    return modes~=nil and #modes>1
end

function T.state(part,tpl)
    local md=part:getModData()
    local s=md.TWDevice
    local count=#T.modes(tpl)
    if type(s)~='table' then
        s={mode=2,on=false}
        md.TWDevice=s
    end
    if type(s.mode)~='number' or s.mode~=math.floor(s.mode) or s.mode<2 or s.mode>count then s.mode=2 end
    s.on=s.on==true
    return s
end

function T.emitters(part,tpl)
    if not T.isDevice(tpl) then return {} end
    local s=T.state(part,tpl)
    if not s.on then return {} end
    return T.modes(tpl)[s.mode] or {}
end

function T.devices(tree,parts)
    local list={}
    for path,tpl in pairs(tree) do
        if T.isDevice(tpl) and parts[path] then list[#list+1]={path=path,tpl=tpl,part=parts[path]} end
    end
    table.sort(list,function(a,b) return a.path<b.path end)
    return list
end

-- Mode label keys for UI and context menu.
function T.modeKey(tpl,mode)
    local kinds={}
    for _,e in ipairs(T.modes(tpl)[mode] or {}) do kinds[e.kind]=true end
    local parts={}
    for _,kind in ipairs({'light','laser','ir','irlight'}) do if kinds[kind] then parts[#parts+1]=kind end end
    return #parts>0 and table.concat(parts,'+') or 'none'
end

-- Night vision of the local viewer: a Tarkov night vision scope while aiming
-- (TW/OpticView sets T.opticView), the game's own goggles flag (night vision
-- mods), or Beathoven's Night Vision Framework, whose own modes keep the game
-- flag off. A thermal scope shows no IR light.
function T.viewerNVG(player)
    if isServer() then return false end
    player=player or getPlayer()
    if not player then return false end
    local view=T.opticView and T.opticView(player)
    if view=='thermal' then return false end
    if view=='nv' then return true end
    if player.isWearingNightVisionGoggles~=nil and player:isWearingNightVisionGoggles() then return true end
    local nv=rawget(_G,'NV')
    return type(nv)=='table' and type(nv.State)=='table' and nv.State.isActive==true
end

-- The viewer looks through a Tarkov thermal scope: no light, laser or IR beam.
function T.viewerThermal(player)
    if isServer() then return false end
    player=player or getPlayer()
    return player~=nil and T.opticView~=nil and T.opticView(player)=='thermal'
end

-- Native torch values from Tarkov Light data; the cone (TorchDot) is fixed in the item script.
function T.lightValues(e)
    local range=e.range or 30
    local intensity=e.intensity or 1.2
    local distance=math.floor(math.min(18,math.max(8,range*0.4))+0.5)
    local strength=math.min(2,math.max(0.8,intensity))
    if e.kind=='irlight' then strength=strength*0.8 end
    return strength,distance
end

function T.prepare(weapon,key,tree,parts)
    local nvg=T.viewerNVG()
    local best,bestPart,bestScore
    local devices=T.devices(tree,parts)
    for _,d in ipairs(devices) do
        d.part:setCanBeActivated(true)
        -- Tarkov devices have no batteries; keep the native charge full.
        if d.part.setCurrentUsesFloat then d.part:setCurrentUsesFloat(1) end
        for _,e in ipairs(T.emitters(d.part,d.tpl)) do
            if e.kind=='light' or (e.kind=='irlight' and nvg) then
                local score=(e.range or 30)*(e.intensity or 1)+(e.kind=='light' and 1000 or 0)
                if not bestScore or score>bestScore then best,bestPart,bestScore=e,d.part,score end
            end
        end
    end
    for _,d in ipairs(devices) do
        if d.part~=bestPart then
            d.part:setLightStrength(0)
            d.part:setLightDistance(0)
            d.part:setTorchCone(false)
            d.part:setActivated(false)
        end
    end
    if bestPart then
        local strength,distance=T.lightValues(best)
        bestPart:setLightStrength(strength)
        bestPart:setLightDistance(distance)
        bestPart:setTorchCone(true)
        if weapon:getActiveLight()~=bestPart then weapon:setActiveLight(bestPart) end
        if not weapon:isActivated() then weapon:setActivated(true) end
        T.torch[weapon]={part=bestPart,strength=strength,distance=distance,cone=true}
    else
        if weapon:getActiveLight() then
            weapon:setActivated(false)
            weapon:setActiveLight(nil)
        end
        T.torch[weapon]=nil
    end
    T.nvg[weapon]=nvg
    return bestPart
end

-- The native PZ torch always shines along the character's look vector
-- (IsoGameCharacter.TorchInfo). While aiming that is the bore; with the weapon
-- lowered the B42 pose points the bore aside, so the torch turns into a weak
-- spill around the character and the Tarkov beam follows the bore
-- (TW/LightRender). Applied only when the state changes.
T.torch=setmetatable({},{__mode='k'})
T.spill={distance=.3,strength=.5}
function T.aimLight(weapon,cone)
    local t=weapon and T.torch[weapon]
    if not t or t.cone==cone then return end
    if cone then
        t.part:setLightDistance(t.distance)
        t.part:setLightStrength(t.strength)
    else
        t.part:setLightDistance(math.max(3,math.floor(t.distance*T.spill.distance+.5)))
        t.part:setLightStrength(t.strength*T.spill.strength)
    end
    t.part:setTorchCone(cone)
    t.cone=cone
end

-- Visible beams for a weapon: kind, colour, emitter point and housing forward axis in root model space.
function T.beams(key,tree,parts)
    local result={}
    for _,d in ipairs(T.devices(tree,parts)) do
        local placement,direction=nil,nil
        for _,e in ipairs(T.emitters(d.part,d.tpl)) do
            if e.kind=='laser' or e.kind=='ir' then
                if not placement then
                    placement=C.placement(key,tree,d.path)
                    if placement then
                        local fx,fy,fz=placement[1][2],placement[2][2],placement[3][2]
                        local len=math.sqrt(fx*fx+fy*fy+fz*fz)
                        if len>1e-6 then
                            fx,fy,fz=fx/len,fy/len,fz/len
                            direction=(math.abs(fx)<1e-5 and fy>.999999 and math.abs(fz)<1e-5) and {0,1,0} or {fx,fy,fz}
                        else
                            direction={0,1,0}
                        end
                    end
                end
                if placement then
                    result[#result+1]={kind=e.kind,color=e.color or {1,0,0},position=C.transformPoint(placement,e.position),
                        direction=direction,part=d.part,path=d.path,maxDistance=e.maxDistance,pointClose=e.pointSizeClose,pointFar=e.pointSizeFar}
                end
            end
        end
    end
    return result
end

-- Switched-on lights of a weapon with their Tarkov effects (TW/LightRender):
-- kind (light, irlight), colour, Unity spot values and the lens point in root
-- model space. All Tarkov tactical lights shine along the bore.
function T.lights(key,tree,parts)
    local result={}
    for _,d in ipairs(T.devices(tree,parts)) do
        local placement=nil
        for _,e in ipairs(T.emitters(d.part,d.tpl)) do
            if e.kind=='light' or e.kind=='irlight' then
                placement=placement or C.placement(key,tree,d.path)
                if placement then
                    result[#result+1]={kind=e.kind,color=e.color or {1,1,1},position=C.transformPoint(placement,e.position),
                        part=d.part,path=d.path,range=e.range or 30,spotAngle=e.spotAngle or 60,intensity=e.intensity or 1,fx=e.fx or {}}
                end
            end
        end
    end
    return result
end

-- Client actions. The network message is sent by TW/LaserSync when loaded.
local function read(weapon)
    local R=TWRuntime
    local key=R and R.visualKey(weapon)
    if not key then return end
    local tree,_,parts=R.read(weapon)
    return key,tree,parts
end

-- The switch click of the Tarkov device (TW_Sounds). HandWeapon's own
-- playActivateDeactivateSound reads the weapon's SoundMap, which Tarkov
-- weapons do not have: the devices carry it.
T.sounds={on='TW_DeviceOn',off='TW_DeviceOff',mode='TW_DeviceMode'}
local function changed(player,weapon,key,tree,parts,sound)
    T.prepare(weapon,key,tree,parts)
    if sound and player and player.playSound then player:playSound(T.sounds[sound]) end
    if T.send then T.send(player,weapon,true) end
end

function T.toggle(player,weapon)
    local key,tree,parts=read(weapon)
    if not key then return false end
    local devices=T.devices(tree,parts)
    if #devices==0 then return false end
    local anyOn=false
    for _,d in ipairs(devices) do if T.state(d.part,d.tpl).on then anyOn=true end end
    for _,d in ipairs(devices) do T.state(d.part,d.tpl).on=not anyOn end
    changed(player,weapon,key,tree,parts,anyOn and 'off' or 'on')
    return true
end

function T.cycle(player,weapon)
    local key,tree,parts=read(weapon)
    if not key then return false end
    local devices=T.devices(tree,parts)
    if #devices==0 then return false end
    for _,d in ipairs(devices) do
        local s=T.state(d.part,d.tpl)
        local count=#T.modes(d.tpl)
        s.mode=s.mode>=count and 2 or s.mode+1
    end
    changed(player,weapon,key,tree,parts,'mode')
    return true
end

function T.set(player,weapon,part,mode,on)
    local key,tree,parts=read(weapon)
    if not key then return false end
    for _,d in ipairs(T.devices(tree,parts)) do
        if d.part==part then
            local s=T.state(part,d.tpl)
            if mode then s.mode=mode end
            if on~=nil then s.on=on end
            T.state(part,d.tpl)
            changed(player,weapon,key,tree,parts,on==true and 'on' or on==false and 'off' or 'mode')
            return true
        end
    end
    return false
end

-- Re-evaluate IR illumination when the local viewer puts night vision on/off.
function T.refreshNVG(player)
    if isServer() then return end
    local weapon=player:getPrimaryHandItem()
    if not weapon or not TWRuntime or not TWRuntime.visualKey(weapon) then return end
    if T.nvg[weapon]~=T.viewerNVG() then
        local key,tree,parts=read(weapon)
        if key then T.prepare(weapon,key,tree,parts) end
    end
end

return T
