-- Local presentation option; gameplay does not depend on HUD visibility.
require('TW_Tactical')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addTickBox('ArmStaminaBar',getText('UI_TW_ArmStaminaBar'),true,getText('UI_TW_ArmStaminaBar_tooltip'))
return options
