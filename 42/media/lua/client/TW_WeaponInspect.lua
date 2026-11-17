require('TW_Tactical')
local M=require('TW_FireModeSwitch')
local H=require('TW/HoldMenu')
local Combo=require('TW_KeyCombo')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
-- B/L/T are occupied by vanilla crafting, skills and chat. FB3 (user's item 2, 18.10.2026): ';'
-- and '\' were taken by Shadow Cat Tactics: Dispatch (menu, hide UI) and ';' / ''' by Viewpoint
-- (shoulder swap, overlay); the defaults moved to '.' and '/'. The fire mode needs no key of its
-- own: Gunworks' fire mode key (T) already switches it through M.request (TW_FireModeSwitch).
-- Every binding stays rebindable (TW_OptionsLayout moves old saved defaults once) and may be a
-- combination (Shift/Ctrl/Alt + key): TW_KeyCombo stores, shows and matches the modifier (item 6).
options:addKeyBind('WeaponInspect',getText('UI_TW_WeaponInspect'),Keyboard.KEY_PERIOD)
options:addKeyBind('CheckChamber',getText('UI_TW_CheckChamber'),Keyboard.KEY_SLASH)
options:addKeyBind('MagazineCheck',getText('UI_TW_MagazineCheck'),Keyboard.KEY_COMMA)
options:addKeyBind('FireModeSwitch',getText('UI_TW_FireModeSwitch'),0)
options:addKeyBind('FireModeCheck',getText('UI_TW_FireModeCheck'),0)
options:addDescription(getText('UI_TW_WeaponUtilityHelp'))

function M.key(key)
    local player=getSpecificPlayer(0)
    if not H.free(player) then return end
    local gun=player:getPrimaryHandItem()
    if Combo.matches('WeaponInspect',key) then M.request(player,gun,'Inspect')
    elseif Combo.matches('CheckChamber',key) then M.request(player,gun,'Chamber')
    -- Empty hands or a non-firearm: the reload radial's own functions reject it (they did
    -- not, and the magazine-check key threw "isContainsClip of non-table: null").
    elseif Combo.matches('MagazineCheck',key) then if gun then require('TW_ReloadRadial').checkMagazine(player,gun) end
    elseif Combo.matches('FireModeSwitch',key) then M.switch(player)
    elseif Combo.matches('FireModeCheck',key) then M.request(player,gun,'ModeCheck') end
end
Events.OnKeyPressed.Add(M.key)
Events.OnPlayerUpdate.Add(function(player)
    local action=M.active[player]
    local attack=player:getJoypadBind()~=-1 and player:isAttackButtonDown()
    if action and action.utility~='Switch' and (isMouseButtonDown(0) or attack) then action:forceStop() end
end)
return M
