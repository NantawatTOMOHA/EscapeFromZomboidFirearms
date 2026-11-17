-- Cartridges where Tarkov shows them (FB4-A, user report 19.10.2026, item 7: "the chamber is always shown empty,
-- as are the magazines, without cartridge models"): the chambered round on the weapon's patron_in_weapon node(s), a
-- magazine's rounds on its patron_NNN nodes from the lips down, by the real count and the real round types (Gunworks
-- AmmoList: the last one fires next, the chambered one last), on the drawn weapon, on its installed magazine and on
-- the magazine in the support hand (a reload, a top-off) - one model tree, so isometric and Viewpoint alike.
-- Tarkov's prefabs carry nodes only for the rounds that can be seen (an opaque STANAG its top 3, a windowed PMAG all
-- 30, no shotgun tube any): min(rounds, nodes) is Tarkov's own display. Display only (Java CartridgeVisual): no item,
-- ammunition or part changes. Frames: TW/Data/CartridgeNodes (tools/cartridge_nodes.py); round models: the
-- delivered Tarkov rounds (MLA_<item>, FB1-A), mounted on a node as a part on its slot (self attachment 'origin').
local C=require('TW/Core')
local R=require('TW/Runtime')
local D=C.data
local N=require('TW/Data/CartridgeNodes')
local F=require('TW/Data/BeltFeed')
local M={sent=setmetatable({},{__mode='k'}),scripts={},names={},errors=0,updates=0}
TWCartridgeVisual=M
-- Loaded on first use (a require inside the per-tick code scanned the game's list of loaded files every call).
local Render,ReloadVisual
local function render() Render=Render or require('TW/Render');return Render end
local function reloadVisual() ReloadVisual=ReloadVisual or require('TW/ReloadVisual');return ReloadVisual end
-- Other players' rounds within this many tiles of the viewer, renewed every othersEvery ticks.
M.radius=20
M.othersEvery=10

local function rows(m)
    return {{m[1],m[2],m[3],m[4]},{m[5],m[6],m[7],m[8]},{m[9],m[10],m[11],m[12]}}
end

-- An attachment of a model script with a frame (3x4 rows), added once (the scripts stay as generated).
local function attach(script,id,frame)
    local a=script:getAttachmentById(id)
    if not a then a=ModelAttachment.new(id);script:addAttachment(a) end
    local offset,rotate,scale=C.attachmentValues(frame)
    a:getOffset():set(offset[1],offset[2],offset[3])
    a:getRotate():set(rotate[1],rotate[2],rotate[3])
    a:setScale(scale)
end

-- A host model script with its node attachments (prefix_1..n), once per script; false without it.
local function host(name,prefix,frames,key)
    local known=M.scripts[name]
    if known~=nil then return known end
    local script=getScriptManager():getModelScript(name)
    if script then
        for i,f in ipairs(frames) do attach(script,prefix..i,key and C.renderMatrix(key,rows(f)) or rows(f)) end
    end
    M.scripts[name]=script~=nil
    return script~=nil
end

-- FB5 (the user's item 1, 20.10.2026: "magazines and the chamber are still empty"): a round of another mod or of the
-- vanilla game that the calibre accepts (ammo.json aliases: SWMG 5.45, Base.545x39Bullets...) has no Tarkov round of
-- its own - it shows its calibre's standard round instead of nothing.
local standard
local function aliasRound(full)
    if not standard then
        standard={}
        for _,calibre in pairs(D.ammo and D.ammo.calibres or {}) do
            local std
            for _,round in ipairs(calibre.rounds or {}) do
                if round.role=='STD' then std=tostring(round.type) end
            end
            if std then for _,alias in ipairs(calibre.aliases or {}) do standard[alias]=std end end
        end
    end
    return standard[full]
end

-- The round model of an ammunition item (the delivered Tarkov round of FB1-A's MLA_ aliases), or nil.
function M.roundModel(full)
    local tpl=type(full)=='string' and N.items[full]
    if not tpl then
        local std=type(full)=='string' and aliasRound(full)
        return std and std~=full and N.items[std] and M.roundModel(std) or nil
    end
    if not N.rounds[tpl] then return nil end
    local name=M.names[full]
    if not name then name='EscapeFromZomboidFirearms.MLA_'..full:gsub('%.','_');M.names[full]=name end
    local known=M.scripts[name]
    if known==nil then
        local script=getScriptManager():getModelScript(name)
        known=script~=nil and script:getAttachmentById('origin')~=nil
        M.scripts[name]=known
    end
    return known and name or nil
end

local function ammoList(item)
    local list=item and item:getModData().AmmoList
    return type(list)=='table' and list or {}
end

local function ammoKey(item)
    local t=item and item.getAmmoType and item:getAmmoType()
    return t and t.getItemKey and t:getItemKey() or nil
end

-- count rounds from the top: the item types of list from index top downwards (the default type past its start).
local function rounds(count,list,top,default,nodes,prefix)
    local out={}
    for i=1,math.min(count,nodes) do
        local full=list[top-i+1] or default
        out[#out+1]={M.roundModel(full),prefix..i}
    end
    return out
end

-- W9-breakopen: single-shot non-magazine weapons (MP18, FN40GL, RSHG2) have HaveChamber=false and no
-- mod_magazine slot, so their loaded round sits directly in currentAmmoCount on tw_chamber_1.
local magSlot={}
local function hasMagazineSlot(key)
    local v=magSlot[key]
    if v==nil then
        v=false
        local w=D.weapons and D.weapons[key]
        local item=w and D.items and D.items[w.tpl]
        for _,s in ipairs(item and item.slots or {}) do if s.name=='mod_magazine' then v=true;break end end
        magSlot[key]=v
    end
    return v
end

-- The desired rounds of a character: {weapon={model,anchor,rounds}, magazine=..., hand=...}.
function M.desired(player,local_)
    local gun=player:getPrimaryHandItem()
    local key=gun and R.key(gun)
    local out={}
    if not key then return out end
    local list=ammoList(gun)
    local chambered=gun:isRoundChambered()
    local loaded=gun:getCurrentAmmoCount()
    local default=ammoKey(gun)
    local sprite=gun:getWeaponSprite()
    -- Immersive Snow compatibility (TW_SnowCompat): the snow mod draws its copy of our root model instead of the
    -- item's own (its Lua getter hides that), so the chamber nodes must go on the script that is really drawn.
    local snow=rawget(_G,'TWSnow')
    if snow and snow.drawnName then sprite=snow.drawnName(gun) or sprite end
    local chamber=N.weapons[key]
    -- one chamber (a magazine weapon): its magazine holds the loaded rounds; a cylinder or barrels hold them all
    local single=(chamber==nil or #chamber==1) and hasMagazineSlot(key) -- W9-breakopen
    if chamber and sprite and host(sprite,'tw_chamber_',chamber,key) then
        -- One chamber: the round in it. A cylinder or barrels: as many as are loaded (Gunworks counts them as ammo).
        local count=single and (chambered and 1 or 0) or loaded+(chambered and 1 or 0)
        out.weapon={sprite,nil,rounds(count,list,#list,default,#chamber,'tw_chamber_')}
    end
    -- The weapon's magazine rounds: the loaded ones from the list's top below the chambered round.
    local top=#list-(chambered and single and 1 or 0)
    local _,entries=(R.view or R.read)(gun)
    for _,e in ipairs(entries) do
        local frames=e.path=='mod_magazine' and N.magazines[e.tpl]
        if frames then
            local model='EscapeFromZomboidFirearms.M_'..e.tpl
            -- Immersive Snow compatibility (TW_SnowCompat): the installed magazine is drawn from the snow copy of its
            -- model while the weapon is snowed; the round nodes go on that script.
            local snow=rawget(_G,'TWSnow')
            if snow and snow.partModel then model=snow.partModel(gun,model) end
            -- FB8 item 8: a belt box drawn fed (its TW/GeometryState row in the weapon's model list) carries the rounds
            -- on its links in that pose, patron_001 at the feed tray; a box drawn otherwise keeps its own frames.
            local feed=F.feed[e.tpl]
            local owner=feed and render().owner[gun]
            if owner and owner.states and owner.states[e.path]==feed.state and F.rounds[e.tpl] then
                model=model..'_'..feed.state
                frames=F.rounds[e.tpl]
            end
            if host(model,'tw_round_',frames) then
                out.magazine={model,render().anchorName(e.tpl,e.copy),rounds(single and loaded or 0,list,top,default,#frames,'tw_round_')}
            end
        end
    end
    -- The magazine in the support hand: the local player's own reload or top-off.
    if local_ then
        local queue=ISTimedActionQueue.getTimedActionQueue(player)
        local action=queue and queue.queue and queue.queue[1]
        local mag=action and action.magazine
        local meta=mag and mag.getFullType and D.types[mag:getFullType()]
        local frames=meta and N.magazines[meta.tpl]
        if frames then
            local model=action.twMagazineLoading and action.twMagazineLoading.model or 'EscapeFromZomboidFirearms.M_'..meta.tpl
            if host(model,'tw_round_',frames) then
                local magList=ammoList(mag)
                out.hand={model,nil,rounds(mag:getCurrentAmmoCount(),magList,#magList,ammoKey(mag),#frames,'tw_round_')}
            end
        end
        -- FB5 (the user's item 1): an eject, a quick drop or a magazine check takes the weapon's own magazine into the
        -- hand (TW_ReloadVisual: twMag, from TWMagGrab the hand model shows it and the weapon is drawn without it);
        -- its rounds are still the weapon's until the eject's end - they go with it into the hand.
        local m=action and not mag and action.gun==gun and action.twMag
        frames=m and m.tpl and N.magazines[m.tpl]
        if frames and single then
            local model=reloadVisual().modelName(m.tpl)
            if host(model,'tw_round_',frames) then
                out.hand={model,nil,rounds(loaded,list,top,default,#frames,'tw_round_')}
            end
        end
    end
    return out
end

local function signature(h)
    if not h then return '' end
    local s={h[1] or '',h[2] or ''}
    for _,r in ipairs(h[3]) do s[#s+1]=(r[1] or '-')..'@'..r[2] end
    return table.concat(s,'|')
end

function M.update(player,local_)
    local native=rawget(_G,'TWCartridgesNative')
    if not native then return end
    local want=M.desired(player,local_)
    local sent=M.sent[player] or {};M.sent[player]=sent
    for _,kind in ipairs({'weapon','magazine','hand'}) do
        local h=want[kind]
        local sig=signature(h)
        if sent[kind]~=sig then
            sent[kind]=sig
            if h then
                local t={}
                for i,r in ipairs(h[3]) do t[i]={r[1],r[2]} end
                native.set(player,kind,h[1],h[2],t)
            else
                native.set(player,kind,nil,nil,nil)
            end
            M.updates=M.updates+1
        end
    end
end

local ticks=0
local function tick(native)
    for i=0,getNumActivePlayers()-1 do
        local p=getSpecificPlayer(i)
        if p then M.update(p,true) end
    end
    if isClient() and ticks%M.othersEvery==0 then
        local viewer=getPlayer()
        local players=getOnlinePlayers()
        for i=0,(players and players:size() or 0)-1 do
            local p=players:get(i)
            if viewer and p~=viewer and not p:isLocalPlayer() and math.abs(p:getX()-viewer:getX())<=M.radius
                and math.abs(p:getY()-viewer:getY())<=M.radius then M.update(p,false) end
        end
    end
    native.tick()
end
Events.OnTick.Add(function()
    local native=rawget(_G,'TWCartridgesNative')
    if not native then return end
    ticks=ticks+1
    local ok,err=pcall(tick,native)
    if not ok then
        M.errors=M.errors+1
        if M.lastError~=tostring(err) then M.lastError=tostring(err);print('[TW] cartridge visual error: '..M.lastError) end
    end
end)
return M
