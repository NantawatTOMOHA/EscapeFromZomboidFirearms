-- Night vision and thermal in Viewpoint's view (Java VpSensor). Every tick, while
-- Viewpoint shows the world: the night vision or thermal scope channel aimed through
-- (its image values are TW/OpticView's javaView of the mode, the very values of the
-- isometric image; every scope, including 1x, uses its own PIP - or, in the camera zoom of the
-- option ScopePicture (W10 nopip2), the zoomed frame inside the eyepiece), the head device (the game's
-- night vision goggles or Night Vision Framework, shown as a Tarkov device of
-- TW/HeadVision with its eyepiece mask) and whether the viewer sees infrared.
-- Outside Viewpoint's view, or without the Java part, nothing is sent: the isometric
-- image stays TW/OpticView's. Options extend the existing page (no second create).
require('TW_Tactical')
require('TW_ScopePipOptions')
local Optic=require('TW/OpticView')
local Sight=require('TW/SightState')
local T=require('TW/Tactical')
local R=require('TW/Runtime')
local Head=require('TW/HeadVision')
local OpticData=require('TW/Data/Optics')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addTickBox('ViewpointSensorHead',getText('UI_TW_ViewpointSensorHead'),true,getText('UI_TW_ViewpointSensorHead_tooltip'))
local deviceBox=options:addComboBox('ViewpointSensorDevice',getText('UI_TW_ViewpointSensorDevice'),getText('UI_TW_ViewpointSensorDevice_tooltip'))
deviceBox:addItem(getText('UI_TW_ViewpointSensorDeviceAuto'),true)
local P={choices={},qaHead=nil,sent=0}
TWViewpointSensors=P
for _,d in ipairs(Head.devices) do
    if d.kind=='nv' then deviceBox:addItem(d.name);P.choices[#P.choices+1]=d end
end
local heatBox=options:addComboBox('ViewpointSensorHeat',getText('UI_TW_ViewpointSensorHeat'),getText('UI_TW_ViewpointSensorHeat_tooltip'))
heatBox:addItem(getText('UI_TW_ViewpointSensorHeatHalf'),true)
heatBox:addItem(getText('UI_TW_ViewpointSensorHeatFull'))
heatBox:addItem(getText('UI_TW_ViewpointSensorHeatQuarter'))

-- Pixelation blocks across the scope's lens: the isometric image counts Tarkov's
-- blocks * LENS over the view height, where Tarkov's lens is 2/5 of it.
P.LENS_SHARE=.4
P.RANGE=100

local function java()
    local zb=rawget(_G,'TWZB')
    return type(zb)=='table' and zb.vpSensorState and zb or nil
end

function P.device(id)
    for _,d in ipairs(Head.devices) do if d.id==id then return d end end
end

-- The Tarkov device a worn night vision item shows as: by its name (GPNVG-18, PNV-10T, ...),
-- else the option's choice, else the default (binocular eyepieces).
function P.headDevice(player)
    if P.qaHead then return P.device(P.qaHead) end
    local chosen=options:getOption('ViewpointSensorDevice'):getValue()
    if type(chosen)=='number' and chosen>1 and P.choices[chosen-1] then return P.choices[chosen-1] end
    local item
    local nv=Optic.nightVisionFramework()
    if nv and type(nv.GetWornNVItem)=='function' then
        local ok,worn=pcall(nv.GetWornNVItem,player)
        if ok then item=worn end
    end
    if not item and player.getWornItems then
        local worn=player:getWornItems()
        for i=0,worn:size()-1 do
            local it=worn:getItemByIndex(i)
            if it and string.find(string.lower(it:getType()),'nightvision',1,true) then item=it;break end
        end
    end
    local name=item and string.gsub(string.lower(item:getFullType()),'[%-_]','') or ''
    for _,d in ipairs(P.choices) do
        local token=string.gsub(string.lower(d.name),'[%-_]','')
        if name~='' and string.find(name,token,1,true) then return d end
    end
    return P.device(Head.default)
end

-- The head device's image values: Tarkov's night vision (Intensity, Color, noise) or the
-- thermal formula's values with the isometric image's scene term; its eyepiece mask.
function P.headTable(d)
    local t={kind=d.kind,mask=Head.masks[d.mask],maskSize=d.maskSize,maskAspect=Head.maskAspect[d.mask],noise=d.noise}
    if d.kind=='nv' then
        t.intensity=d.intensity;t.r,t.g,t.b=d.color[1],d.color[2],d.color[3]
        t.noiseScale=d.noiseScale;t.gainLimit=Optic.gain(Optic.NV_GAIN)
    else
        t.palette=d.palette;t.coef=d.coef;t.minimum=d.minimum;t.shift=d.shift;t.depthFade=d.depthFade
        t.noise=d.noisy and d.noise or 0;t.pixelBlocks=d.pixelBlocks;t.pixelAlpha=d.pixelBlocks>0 and 1 or 0
        t.env=Optic.ENV;t.gain=Optic.gain(Optic.GAIN);t.envCap=Optic.ENV_CAP
    end
    return t
end

-- The night vision or thermal scope channel aimed through now: javaView of its mode, the
-- picture's key prefix (as ScopePip's request key) and the blocks across the lens.
-- Electronic 1x screens use the same authored-camera PIP as magnified scopes.
function P.scopeTable(player)
    if not (TWZB and TWZB.aimIntent or player.isAiming)(player) or not Optic.enabled() then return end
    local view=Sight.current(player)
    if not view or (view.kind~='nv' and view.kind~='thermal') or not view.imageProfile then return end
    local item=OpticData.items[view.imageProfile.tpl]
    local mode=item and item.modes[view.imageProfile.mode]
    if not mode or mode.kind~=view.kind then return end
    local t=Optic.javaView(player,mode)
    t.prefix=view.weapon:getID()..':'..view.path..':'..view.tpl..':'..view.channel..':'..view.mode..':'
    t.blocks=mode.pixelation and mode.pixelation.blocks*Optic.LENS*P.LENS_SHARE or 0
    return t
end

function P.update()
    local zb=java()
    if not zb then return end
    local player=getSpecificPlayer(0)
    local vp=zb.vpState and zb.vpState() or 0
    if not player or player:isDead() or vp==0 or not (TWViewpoint and TWViewpoint.enabled()) then
        if P.active then zb.vpSensorScope(nil);zb.vpSensorHead(nil);P.active=false end
        P.headShown=false
        zb.vpSensorState(false,false,.5)
        return
    end
    P.active=true
    local on=Optic.enabled()
    local quality=heatBox:getValue()
    local heat=quality==2 and 1 or quality==3 and .25 or .5
    zb.vpSensorState(T.viewerNVG(player)==true,on,heat)
    local scope=on and P.scopeTable(player) or nil
    zb.vpSensorScope(scope)
    local head
    if on and options:getOption('ViewpointSensorHead'):getValue() then
        local nv=player:isWearingNightVisionGoggles() or (Optic.nightVisionFramework() and Optic.nightVisionFramework().State.isActive==true)
        if nv or P.qaHead then
            local d=P.headDevice(player)
            if d then head=P.headTable(d) end
        end
    end
    zb.vpSensorHead(head)
    -- ViewpointSensorHead disables only our head-device pass. Keep the owner's
    -- goggles flag and NVF overlay: native night vision remains as without this
    -- pass. In third person our enabled pass has no eye mask (camera outside it).
    -- Night Vision Framework's own overlay steps aside for the device image (TW/OpticView).
    P.headShown=head~=nil and head.kind=='nv'
    P.sent=P.sent+1
end

Events.OnGameStart.Add(function()
    local zb=java()
    if zb then zb.vpSensorConfig({living=Optic.LIVING,zombie=Optic.ZOMBIE,animal=Optic.ANIMAL,range=P.RANGE}) end
end)
Events.OnTickEvenPaused.Add(P.update)
return P
