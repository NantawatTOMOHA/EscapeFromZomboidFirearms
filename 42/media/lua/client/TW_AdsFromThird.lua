-- Aiming from Viewpoint's third person (W9 user item 8, as the player meant it in W10 user item 3, 27.10.2026):
-- "from the third person only a right mouse button CLICK should enter the sight; HOLDING the right mouse button must
-- keep the ordinary third-person aiming, the way it was before".
--
-- The aim itself is the game's own. While the button is down nothing here touches the aim or the camera: the player
-- gets exactly the ordinary third-person aim of the game and Viewpoint (the body turning toward the screen-centre
-- crosshair, the camera behind the shoulder). The decision is made on the RELEASE, never on the press:
--   * a release before M.CLICK_MAX_MS is a click - the camera enters Viewpoint's real first person, the whole
--     first-person ADS stack (the eye pose FpPose, the ADS camera, reticles, the sight picture and the optical scope
--     image), exactly the view Viewpoint's own first person gives; the aim is latched after the release as in W9;
--   * a release after that is a hold - the view is not switched at all and the ordinary aim simply ends with the
--     release, so a long hold can never flicker into first person.
--
-- The latch: the aim is the game's own, and the bridge latches the engine's forceAim (the flag the game's own
-- toggle-to-aim option edits); a guarded advice keeps the engine from clearing it every frame. The game's own
-- toggle-to-aim option is no exception: its handler flips forceAim only on a RELEASE longer than its own 400 ms click
-- window (TimedInputHandler.MAX_CLICK_TIME; a mouse click only aims while it is held), so a click's aim would end with
-- the release; the module latches it exactly as in the plain mode, and the next click ends it. A hold stays the game's
-- own: with that option on its handler toggles forceAim at a long release, so the ordinary third-person aim stays
-- until the player clicks it off, and that click ends it here (the engine keeps it after a short click) without
-- entering the sight.
--
-- The view is Viewpoint's real first person: its own third-person preference (CameraSettings.ON_FOOT, the setting its
-- key edits) is turned off for the duration by the bridge and put back after, so the whole frame - eye pose, ADS
-- camera, reticles, sight picture, optical scope image - is exactly the first person the player gets with Viewpoint's
-- first person. Nothing here writes View.thirdPerson or the pose data itself.
--
-- Exit: the next right mouse button press (the latched aim ends on that press and the press arms nothing, so its
-- release cannot bring the sight back), or anything that ends the aim - running, a timed action (reload, rack, equip),
-- a weapon change, falling, a vehicle, the pause screen or a modal window, Viewpoint leaving its view. In first person
-- this module does nothing.
--
-- The menu: in Viewpoint third person there is almost always an object under the crosshair, and the vanilla
-- ISObjectClickHandler opens the world context menu on the release of any right click shorter than 500 ms there, so
-- without this every enter and leave click of the module also built that menu. The module wraps
-- ISObjectClickHandler.doRClick at world start and answers ownsRightClick at call time (the engine's Up event and our
-- tick may come in either order on the release frame); a click of the module builds no menu, everything else - first
-- person, the isometric view, the option off, another weapon, no weapon, a hold - keeps the vanilla menu.
require('TW_Tactical')
local R=require('TW/Runtime')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addTickBox('AdsFromThird',getText('UI_TW_AdsFromThird'),true,getText('UI_TW_AdsFromThird_tooltip'))

local M={on={},raw={},aim={},armed={},took={},leave={},qa={},entered=0,exited=0,held=0,cancelled=0,off=0,refused=0,errors=0,
    menu=0,owns=0,lastClick=0,lastError='',last=''}
TWAdsFromThird=M

-- The whole click/hold rule in one number: a release sooner than this is a click, anything longer is a hold. A
-- deliberate mouse click of a player lasts about 40-150 ms (the finger is on the button and the press is short), a
-- deliberate aim hold 0.5 s and more; 250 ms sits between the two with room for a slow click and a fast release,
-- so a click is never read as a hold and holding the aim never lands in the sight.
M.CLICK_MAX_MS=250

local function log(s) print('[TW-TPADS] '..s) end

function M.enabled()
    return options:getOption('AdsFromThird'):getValue()~=false
end

-- Viewpoint support (the master option of this mod) and its view state: 0 isometric or absent, 1 first person,
-- 2 third person. With the aim's override in effect the state is 1, so every check that needs "was third person"
-- reads Viewpoint's own preference instead (TWZB.vpThirdPersonSetting).
local function support()
    local V=rawget(_G,'TWViewpoint')
    if not V then return true end
    local ok,on=pcall(V.enabled)
    return not (ok and on==false)
end

local function viewState()
    local z=rawget(_G,'TWZB')
    if type(z)~='table' or type(z.vpState)~='function' then return nil end
    local ok,state=pcall(z.vpState)
    return ok and tonumber(state) or nil
end

local function bridge(name)
    local z=rawget(_G,'TWZB')
    return type(z)=='table' and type(z[name])=='function' and z[name] or nil
end

-- W11 oldpoint: OldPoint's rotating overhead camera (its own topDownActive, read through the Java bridge) replaces
-- Viewpoint's camera and is not Viewpoint's third person; there the mod's first-person rules stand down and a click must
-- not flip Viewpoint's own flags under that camera (which would leave Viewpoint itself in first person overhead).
local function overhead()
    local f=bridge('vpOverhead')
    if not f then return false end
    local ok,on=pcall(f)
    return ok and on==true
end

-- A mod weapon in the hands of a living local player; the same test every other module uses (TW/Runtime).
local function held(p)
    if not (p and instanceof(p,'IsoPlayer') and p:isLocalPlayer() and not p:isDead()) then return nil end
    local gun=p:getPrimaryHandItem()
    return gun and R.key(gun) and gun or nil
end

-- The raw aim key of the game (mouse, keyboard or gamepad axis; not the latched forceAim), or nil when the
-- Java part does not offer the read: a click then cannot be told from a hold and the option stays inert.
local function nativeAimDown(p)
    local z=rawget(_G,'TWZB')
    local f=type(z)=='table' and z.aimKeyDown or nil
    if type(f)~='function' then return nil end
    local ok,down=pcall(f,p)
    if not ok or type(down)~='boolean' then return nil end
    return down
end

local function aimDown(p)
    local qa=M.qa[p]
    if qa~=nil then return qa==true end
    return nativeAimDown(p)
end

-- The game's own toggle-to-aim option: its handler is the player's own then (PatchViewpoint.AimLatch never skips it)
-- and flips forceAim by itself at the release of a hold longer than its click window; a click only aims while down.
local function engineToggle()
    local ok,on=pcall(function() return getCore():isToggleToAim() end)
    return ok and on==true
end

local function setForce(p,on)
    local f=bridge('aimForce')
    if f then pcall(f,p,on) end
end

-- The engine's own toggled aim ends where the player clicks it off: the flag its own option edits and the state the
-- engine derives from it, so the click has its effect in this frame.
local function endEngineAim(p)
    setForce(p,false)
    pcall(function() p:setIsAiming(false) end)
end

local function blocked(p)
    if not M.enabled() then return 'option' end
    if not support() then return 'support' end
    if overhead() then return 'overhead' end
    if p:isDead() then return 'dead' end
    if p:getVehicle() then return 'vehicle' end
    if p:isSprinting() or p:isFalling() or p:isKnockedDown() then return 'movement' end
    if not (p:getVariableString('BumpFallType')==nil or p:getVariableString('BumpFallType')=='') then return 'fall' end
    local actions=p:getCharacterActions()
    if actions and not actions:isEmpty() then return 'action' end
    if isGamePaused() then return 'paused' end
    if UIManager and UIManager.isModalVisible and UIManager.isModalVisible() then return 'modal' end
    return nil
end

-- True when the short right click the vanilla handler is about to turn into the world context menu is this module's:
-- the click that entered the sight, the click that left it, or the click that ended the game's own toggled aim.
-- Asked at CALL time, because the engine's Up event and our tick can come in either order on the release frame: a
-- press that is still armed and a click the module has already processed both count here. A hold (longer than
-- M.CLICK_MAX_MS) and every state outside the feature - first person, the isometric view, the option off, another
-- weapon, no weapon - stay vanilla, so the menu opens there exactly as before.
function M.ownsRightClick(p)
    p=p or getSpecificPlayer(0)
    if not (p and instanceof(p,'IsoPlayer') and p:isLocalPlayer()) then return false end
    if M.on[p] then
        M.owns=M.owns+1
        return true
    end
    local now=getTimestampMs()
    local a=M.armed[p]
    if a and now-a.at<=M.CLICK_MAX_MS then
        M.owns=M.owns+1
        return true
    end
    local t=M.took[p]
    if t and now-t<=M.CLICK_MAX_MS then
        M.owns=M.owns+1
        return true
    end
    return false
end

-- The vanilla world menu: ISObjectClickHandler.onObjectRightMouseButtonUp calls doRClick for a right click shorter
-- than 500 ms over an object. Wrapped once (the original kept on the table and read through rawget at call time, so
-- a reload of this file reads the current module and a neighbour that wrapped doRClick before us still runs); every
-- click ownsRightClick claims builds no menu and clears the vanilla down state, everything else is the previous
-- function unchanged. First person, the isometric view, other weapons and the option off never reach the own path.
function M.wrapClicks()
    local H=rawget(_G,'ISObjectClickHandler')
    if not (H and type(H.doRClick)=='function') then
        log('world context menu not found: a right click can still open the vanilla menu')
        return false
    end
    if H.twAdsThird then return false end
    H.twAdsThird={doRClick=H.doRClick}
    H.doRClick=function(object,x,y)
        local current=rawget(_G,'TWAdsFromThird')
        local ok,owned=false,false
        if current and type(current.ownsRightClick)=='function' then
            ok,owned=pcall(current.ownsRightClick,getSpecificPlayer(0))
        end
        if ok and owned then
            H.rdownObject=nil
            if current then current.menu=current.menu+1 end
            return
        end
        if not ok and current then
            current.errors=current.errors+1
            current.lastError='menu:'..tostring(owned)
        end
        return H.twAdsThird.doRClick(object,x,y)
    end
    return true
end

-- Ends the override: Viewpoint's own third person comes back first (the camera is third person for this frame),
-- then the aim is released. Nothing is written when the player never had the override.
local function finish(p,why)
    local s=M.on[p]
    if not s then return false end
    M.on[p]=nil
    local restore=bridge('vpAdsFirstPerson')
    if restore then pcall(restore,false) end
    if s.force then setForce(p,false) end
    M.exited=M.exited+1
    M.last=tostring(why or '')
    log(string.format('exit why=%s clickMs=%d latchedMs=%d exited=%d',tostring(why),s.clickMs or -1,
        s.at and getTimestampMs()-s.at or -1,M.exited))
    return true
end

-- A press that stops being a possible click before the release: the aim ended on its own, the player ran, a timed
-- action started, the weapon changed or Viewpoint left its view. The release then does nothing at all.
local function cancel(p,why)
    local a=M.armed[p]
    if not a then return false end
    M.armed[p]=nil
    M.cancelled=M.cancelled+1
    M.last=tostring(why or 'cancel')
    log(string.format('cancel why=%s heldMs=%d',tostring(why),getTimestampMs()-a.at))
    return true
end

local function enter(p,gun,clickMs)
    local override=bridge('vpAdsFirstPerson')
    if not override then M.refused=M.refused+1;M.last='no-java';return false end
    local ok,switched=pcall(override,true)
    if not ok or switched~=true then M.refused=M.refused+1;M.last='refused';return false end
    -- The aim of the click is ours in both modes: the engine takes it down with the release (with its own
    -- toggle-to-aim option on, a short mouse click does not flip its flag), so the latch - and the aim - is ours
    -- from this tick on.
    setForce(p,true)
    pcall(function() p:setIsAiming(true) end)
    M.on[p]={gun=gun,force=true,clickMs=clickMs,at=getTimestampMs()}
    M.took[p]=getTimestampMs()
    -- The aim is up from this tick on: the latched aim must never look like a new press to the edge detection.
    M.aim[p]=true
    M.entered=M.entered+1
    M.last='enter'
    log(string.format('enter clickMs=%d force=%s gun=%s entered=%d',clickMs,tostring(M.on[p].force),
        tostring(gun:getFullType()),M.entered))
    return true
end

-- The release decides: a click enters the sight, a hold is ordinary third-person aiming only.
local function click(p,now)
    local a=M.armed[p]
    M.armed[p]=nil
    local ms=now-a.at
    M.lastClick=ms
    -- The press that ends the game's own toggled aim: its click ends that aim and never enters the sight. The
    -- engine keeps such an aim after a short mouse click (its handler flips the flag only on a longer release), so
    -- the click is taken here; a longer release is the engine's own toggle and is left to it.
    if a.off then
        if ms<=M.CLICK_MAX_MS and not blocked(p) then
            endEngineAim(p)
            M.took[p]=now
            M.off=M.off+1
            M.last='toggle-off'
            log(string.format('off ms=%d aim=%s view=%s off=%d',ms,tostring(p:isAiming()),tostring(viewState()),M.off))
            return false
        end
        M.held=M.held+1
        M.last='hold'
        log(string.format('hold ms=%d aim=%s view=%s held=%d',ms,tostring(p:isAiming()),tostring(viewState()),M.held))
        return false
    end
    if ms<=M.CLICK_MAX_MS and a.aimed and a.gun==p:getPrimaryHandItem() and viewState()==2 and not blocked(p) then
        return enter(p,a.gun,ms)
    end
    if ms<=M.CLICK_MAX_MS then
        M.cancelled=M.cancelled+1
        M.last='click-refused'
        log(string.format('click not taken ms=%d aimed=%s view=%s',ms,tostring(a.aimed),tostring(viewState())))
        return false
    end
    M.held=M.held+1
    M.last='hold'
    log(string.format('hold ms=%d aim=%s view=%s held=%d',ms,tostring(p:isAiming()),tostring(viewState()),M.held))
    return false
end

local function update(p)
    if not (p and instanceof(p,'IsoPlayer') and p:isLocalPlayer()) then return end
    local now=getTimestampMs()
    local raw=aimDown(p)
    local aiming=p:isAiming()==true
    local lastRaw=M.raw[p]
    local lastAim=M.aim[p]
    M.raw[p]=raw
    M.aim[p]=aiming
    local pressed,released
    if raw~=nil then
        pressed=lastRaw~=nil and raw and not lastRaw
        released=lastRaw==true and not raw
    end
    -- A new press is not the click of the module's own that has just been processed: the record below never leaks
    -- into a later, unrelated right click.
    if pressed==true then M.took[p]=nil end
    -- The release of the press that left the sight: within the click window it is the module's leave click, and the
    -- record is renewed at the RELEASE, so a frame that delays the vanilla Up event cannot lose it (the engine sends
    -- the Up on the release frame, but the order with this tick is not guaranteed).
    if released==true then
        local l=M.leave[p]
        if l then
            if now-l<=M.CLICK_MAX_MS then M.took[p]=now end
            M.leave[p]=nil
        end
    end
    local s=M.on[p]
    if s then
        if pressed==true then M.leave[p]=now;M.took[p]=now;finish(p,'press');return end
        if not aiming then finish(p,'aim');return end
        if viewState()~=1 then finish(p,'view');return end
        local why=blocked(p)
        if why then finish(p,why);return end
        if p:getPrimaryHandItem()~=s.gun then finish(p,'weapon');return end
        return
    end
    local a=M.armed[p]
    if a then
        if aiming then a.aimed=true end
        if released then click(p,now);return end
        if not (aiming or raw) then cancel(p,'aim-ended');return end
        local why=blocked(p)
        if why then cancel(p,why);return end
        if viewState()~=2 then cancel(p,'view');return end
        if p:getPrimaryHandItem()~=a.gun then cancel(p,'weapon');return end
        return
    end
    if not (M.enabled() and support()) then return end
    -- Without the raw key read (a Java part older than this feature) a press cannot be told from the aim state
    -- itself: the aim is on while the button is held and our own latch keeps it on afterwards, so the next press
    -- could never be seen. The option stays inert there instead of latching an aim that cannot be clicked off.
    if raw==nil then return end
    local gun=held(p)
    if not gun then return end
    if viewState()~=2 then return end
    if pressed~=true then return end
    -- The press that ends the game's own toggled aim (its toggle-to-aim option's latched aim) is not a new aim: with
    -- that option on the aim was already up when the button went down. Such a press is armed as the end of that aim -
    -- its click ends it (see click) and never enters the sight, its hold is the engine's own toggle.
    if engineToggle() and lastAim then
        M.armed[p]={gun=gun,at=now,aimed=aiming,off=true}
        M.last='arm-off'
        return
    end
    local why=blocked(p)
    if why then return end
    -- Nothing is changed here: the player is in the ordinary third-person aim until the release decides.
    M.armed[p]={gun=gun,at=now,aimed=aiming}
    M.last='arm'
    return
end

Events.OnTickEvenPaused.Add(function()
    if not (TWZB and TWZB.vpState) then return end
    local ok,err=pcall(function()
        for i=0,getNumActivePlayers()-1 do update(getSpecificPlayer(i)) end
    end)
    if not ok then
        M.errors=M.errors+1
        M.lastError=tostring(err)
        for p in pairs(M.on) do finish(p,'error') end
        for p in pairs(M.armed) do M.armed[p]=nil end
    end
end)

-- Nothing of ours survives the world, a death or a lost player: the camera returns to third person at once.
local function drop(p)
    if p==nil then
        for other in pairs(M.on) do finish(other,'reset') end
        for other in pairs(M.armed) do M.armed[other]=nil end
        for other in pairs(M.took) do M.took[other]=nil end
        for other in pairs(M.leave) do M.leave[other]=nil end
        return
    end
    finish(p,'reset')
    M.armed[p]=nil
    M.took[p]=nil
    M.leave[p]=nil
    M.raw[p]=nil;M.aim[p]=nil;M.qa[p]=nil
end
Events.OnGameStart.Add(function()
    drop(nil)
    M.wrapClicks()
    local setting=bridge('vpThirdPersonSetting')
    if not setting or setting()<0 then
        log('Viewpoint third-person aim unavailable: the Java bridge or its Viewpoint members are missing; the option stays inert')
    elseif not bridge('aimKeyDown') then
        log('Viewpoint third-person aim unavailable: the Java part cannot read the aim key, so a click cannot be told from a hold; the option stays inert')
    end
end)
Events.OnCreatePlayer.Add(function(_,p) drop(p) end)
Events.OnPlayerDeath.Add(function(p) drop(p) end)

-- QA only (qa/TWQA, never shipped): the raw aim key of a player without touching the mouse, the same way
-- hip fire QA renews its button. nil clears the injection.
function M.qaAim(p,down)
    if down==nil then M.qa[p]=nil else M.qa[p]=down and true or false end
end
function M.qaReset()
    for p in pairs(M.qa) do M.qa[p]=nil end
end

function M.describe(p)
    p=p or getSpecificPlayer(0)
    local s=M.on[p]
    local a=M.armed[p]
    local setting=bridge('vpThirdPersonSetting')
    return string.format('on=%s armed=%s weapon=%s force=%s view=%s setting=%s raw=%s aiming=%s lastClick=%d last=%s entered=%d exited=%d held=%d cancelled=%d off=%d refused=%d menu=%d owns=%d errors=%d %s',
        tostring(s~=nil),tostring(a~=nil),s and s.gun:getFullType() or '-',tostring(s and s.force),
        tostring(viewState()),tostring(setting and setting() or '?'),tostring(M.raw[p]),tostring(p and p:isAiming()),
        M.lastClick,M.last,M.entered,M.exited,M.held,M.cancelled,M.off,M.refused,M.menu,M.owns,M.errors,M.lastError)
end

function M.stats()
    return string.format('entered=%d exited=%d held=%d cancelled=%d off=%d refused=%d menu=%d owns=%d errors=%d lastClick=%d last=%s %s',
        M.entered,M.exited,M.held,M.cancelled,M.off,M.refused,M.menu,M.owns,M.errors,M.lastClick,M.last,M.lastError)
end

return M
