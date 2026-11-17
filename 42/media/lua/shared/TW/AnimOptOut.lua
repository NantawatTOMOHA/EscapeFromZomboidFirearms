-- W8 animopt (the user's item 9): the state of the two client options that give the weapon animations
-- entirely back to vanilla and other animation mods - one for the third person (the isometric view and
-- Viewpoint's third person), one for Viewpoint's first person. Both are off by default, where the mod
-- poses as before.
--
-- This module only holds the state and answers one question for every animation/pose layer:
-- "may the mod pose this character in this view now?". TW_AnimOptOut.lua (client) registers the two
-- option boxes, reads them, keeps them across a restart before the options screen is opened and pushes
-- them to the Java part (AnimOptOut.java through TWZB.animOptOut), whose layers ask the same question.
-- The layers keep their data and their timed actions: with an option on they only stop writing bones,
-- hands and hand props in that view, so the vanilla animation (and other mods' animations) show through
-- and the weapon stays drawn on the vanilla hand bone.
--
-- Views are ViewpointBridge.state()'s: 0 isometric (or no Viewpoint), 1 Viewpoint first person,
-- 2 Viewpoint third person. A character that is not the local player is always drawn in the third
-- person, so the third-person option alone decides it for remote players.
local M={third=false,first=false,viewCalls=0,errors=0}
TWAnimOptOutState=M

local function api()
    local z=rawget(_G,'TWZB')
    if type(z)=='table' and type(z.vpState)=='function' then return z end
end

-- The current view (0 isometric, 1 Viewpoint first person, 2 Viewpoint third person).
function M.view()
    local z=api()
    if not z then return 0 end
    M.viewCalls=M.viewCalls+1
    local ok,value=pcall(z.vpState)
    value=ok and tonumber(value) or nil
    if value==1 or value==2 then return value end
    return 0
end

-- The option that governs a view: the first-person one in Viewpoint's first person, the third-person
-- one in the isometric and Viewpoint's third-person views.
function M.blocksFor(view)
    view=tonumber(view) or M.view()
    if view==1 then return M.first end
    return M.third
end

-- W9-inertia (Hardship Inertia): the same yield, for one character at a time. A compatibility module
-- registers a predicate here and every node/layer of the mod treats a character it answers true for as
-- if the view's option were on: no stance/contact/carry node, no grip or first-person pose on that
-- character, the body left to the clips of the mod that owns it. The predicate is asked by
-- M.poses(player) and pushed to the Java part by the module that registers it (TWZB.animOptOutYield).
-- Unlike the options it is not a view: it is the character's own state (lying, crawling...).
M.yielders={}
M.yieldNames={}

-- Register (or replace) the predicate of a module: test(character) -> true while that character's
-- animation belongs to someone else. Returns nothing.
function M.yieldFor(name,test)
    if type(name)~='string' or type(test)~='function' then error('yieldFor(name, test)',2) end
    if not M.yielders[name] then M.yieldNames[#M.yieldNames+1]=name end
    M.yielders[name]=test
end

function M.clearYield(name)
    if M.yielders[name] then
        M.yielders[name]=nil
        for i=#M.yieldNames,1,-1 do if M.yieldNames[i]==name then table.remove(M.yieldNames,i) end end
    end
end

-- Does any registered module own this character's animation now?
function M.yielded(player)
    if player==nil then return false end
    for i=1,#M.yieldNames do
        local test=M.yielders[M.yieldNames[i]]
        if test then
            local ok,value=pcall(test,player)
            if ok and value==true then return true end
        end
    end
    return false
end

-- The mod must not pose, override an animation or draw a hand prop in the current view.
function M.blocks()
    if not (M.third or M.first) then return false end
    return M.blocksFor(M.view())
end

-- First-person vanilla animations still allow the existing sight alignment while Aim is held.
-- This exception belongs only to the ADS pose entry; every general animation/IK caller still yields.
function M.keepsAds(player)
    if not M.first or not player or M.view()~=1 or M.yielded(player) then return false end
    local ok,value=pcall(function()
        if not player:isLocalPlayer() then return false end
        if player.getPlayerNum and player:getPlayerNum()~=0 then return false end
        local z=rawget(_G,'TWZB')
        return (z and z.aimIntent or player.isAiming)(player)==true
    end)
    return ok and value==true
end

-- Do the two W8 "vanilla animations" options themselves forbid posing this character in the current view?
-- Unlike not M.poses(player), a per-character yield (M.yielded) does not count here.
function M.optionsBlock(player)
    if not (M.third or M.first) then return false end
    local local_=player and player.isLocalPlayer
    if type(local_)=='function' then
        local ok,value=pcall(local_,player)
        if ok and value~=true then return M.third end
    end
    return M.blocks()
end

-- May the mod pose this character in the current view? A remote character is always third person.
-- W9-inertia: a character another mod owns (M.yielded) is never posed, whatever the view.
function M.poses(player)
    if player~=nil and M.yielded(player) then return false end
    if not (M.third or M.first) then return true end
    local local_=player and player.isLocalPlayer
    if type(local_)=='function' then
        local ok,value=pcall(local_,player)
        if ok and value~=true then return not M.third end
    end
    return not M.blocks()
end

-- The state the client module (and QA) publishes: true = that view's animations belong to vanilla.
function M.set(third,first)
    M.third=third==true
    M.first=first==true
end

function M.reset()
    M.third,M.first=false,false
end

-- QA: "third=true first=false blocks(view1)=false poses(local)=true ..."
function M.stats(player)
    local function b(view) return tostring(M.blocksFor(view)) end
    return string.format('third=%s first=%s view=%d blocks0=%s blocks1=%s blocks2=%s poses=%s yielded=%s',
        tostring(M.third),tostring(M.first),M.view(),b(0),b(1),b(2),
        player~=nil and tostring(M.poses(player)) or '-',
        player~=nil and (tostring(M.yielded(player))..'['..table.concat(M.yieldNames,',')..']') or '-')
end

return M
