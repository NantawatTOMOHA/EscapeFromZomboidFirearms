-- The mod options page "Audio" (W10, the player's item 20: "add a separate Audio tab with sliders for the
-- volume of gunshots, reloading, the bolt, loading magazines and so on"). One slider per sound class of the
-- mod, 0..200 percent, default 100 - the volume the mod has without this page.
--
-- The sliders only scale what this player hears: TW_Audio multiplies them into the GameSound volume of every
-- sound of the class (never into the world sound radius that attracts zombies and never into another player's
-- setting), and the options screen keeps them in ModOptions.ini as every other option of the mod.
-- TW_OptionsLayout puts them on the page 'EscapeFromZomboidFirearmsAudio' with the help line that explains the
-- percent scale (the vanilla screen draws no tooltip for a slider); the values stay readable through the page
-- they are added to, as the layout requires.
require 'PZAPI/ModOptions'
require 'TW_Audio'

local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')

options:addSlider('AudioShots',getText('UI_TW_AudioShots'),0,200,5,100)
options:addSlider('AudioSuppressed',getText('UI_TW_AudioSuppressed'),0,200,5,100)
options:addSlider('AudioReload',getText('UI_TW_AudioReload'),0,200,5,100)
options:addSlider('AudioBolt',getText('UI_TW_AudioBolt'),0,200,5,100)
options:addSlider('AudioMagLoad',getText('UI_TW_AudioMagLoad'),0,200,5,100)
options:addSlider('AudioHandling',getText('UI_TW_AudioHandling'),0,200,5,100)
options:addSlider('AudioDevices',getText('UI_TW_AudioDevices'),0,200,5,100)
options:addSlider('AudioCasings',getText('UI_TW_AudioCasings'),0,200,5,100)
options:addSlider('AudioExplosions',getText('UI_TW_AudioExplosions'),0,200,5,100)
options:addSlider('AudioImpacts',getText('UI_TW_AudioImpacts'),0,200,5,100)

return options
