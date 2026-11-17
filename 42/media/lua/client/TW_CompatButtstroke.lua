-- Buttstroke (Workshop 3394044313, mod id Buttstroke, by SportXAI) compatibility (W11, the user 29.10.2026).
--
-- Buttstroke turns an aimed shove with a firearm into a gun-stock strike: its AnimSets play Bob_GSA_Rifle /
-- Bob_GSA_Pistol with its swing sound, its Lua (OnHitZombie, server folder) applies the damage, the endurance and
-- the weapon condition. This mod's grip-class shove nodes (m_ConditionPriority 100) outranked its nodes for every
-- weapon of this mod, and this mod's pistol shove files share its file names (whichever mod loads later wins), so
-- the strike never played with these weapons and, in the player's mod order, with no pistol at all.
--
-- The fix is a set of generated twin nodes (tools/buttstroke_compat.py, AnimSets/*__TWButtstroke.xml): this mod's
-- routing (grip class, body, carry transitions) with the animation of the node Buttstroke plays for a vanilla
-- weapon in the same situation, gated by the variable TWButtstroke. This file sets that variable on every player
-- this client animates (the local players and, in multiplayer, the remote ones, whose nodes each client picks
-- itself) while Buttstroke is active - and never without it, so nothing changes then.
--
-- Nothing else is needed on this mod's side: a shove hands the game's swing events the bare hands, not the
-- firearm (SwipeStatePlayer.doAttack), so this mod's shot, recoil and hip-fire handlers never take a stroke for a
-- shot; Buttstroke's own OnWeaponHitCharacter call (with the firearm) has no handler in this mod
-- (tools/check_buttstroke.py keeps both true).
local M = {detected = false, marked = 0, errors = 0}
TWCompatButtstroke = M

M.VAR = 'TWButtstroke'
M.MOD_ID = 'Buttstroke'

function M.active()
    if type(getActivatedMods) ~= 'function' then return false end
    local ok, mods = pcall(getActivatedMods)
    if not ok or not mods then return false end
    for _, id in ipairs({M.MOD_ID, '\\' .. M.MOD_ID}) do
        local found, value = pcall(function() return mods:contains(id) end)
        if found and value == true then return true end
    end
    return false
end

local function mark(player)
    if player and not player:getVariableBoolean(M.VAR) then
        player:setVariable(M.VAR, true)
        M.marked = M.marked + 1
    end
end

-- Every local player's update marks itself and, on a multiplayer client, the remote players.
function M.update(player)
    local ok, err = pcall(function()
        mark(player)
        if isClient and isClient() then
            local players = getOnlinePlayers()
            for i = 0, players:size() - 1 do mark(players:get(i)) end
        end
    end)
    if not ok then M.errors = M.errors + 1; M.lastError = tostring(err) end
end

function M.start()
    M.detected = M.active()
    Events.OnPlayerUpdate.Remove(M.update)
    if M.detected then Events.OnPlayerUpdate.Add(M.update) end
end

Events.OnGameStart.Add(M.start)

return M
