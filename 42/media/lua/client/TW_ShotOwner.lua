-- Optional local owner. Native ammo, events, sound and FX remain in PZ/Gunworks.
require('TW_Tactical')
local C=require('TW/Core')
local R=require('TW/Runtime')
local Sight=require('TW/SightState')
local Identity=require('TW/ShotIdentity')
local M={errors=0,lastError='',sent=false,geometry=setmetatable({},{__mode='k'})}
TWShotOwner=M
-- QA A/B: true rebuilds the request and hands the options to Java every tick, as before.
M.memoOff=false
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
local mode=options:addComboBox('ViewpointBallistics',getText('UI_TW_ViewpointBallistics'),getText('UI_TW_ViewpointBallistics_tooltip'))
mode:addItem(getText('UI_TW_ViewpointBallisticsOff'))
mode:addItem(getText('UI_TW_ViewpointBallisticsShadow'))
-- Live by default in single player (user decision 6, after the clean game and the native MP run);
-- MP always follows the replicated server sandbox policy below (live needs the server companion).
mode:addItem(getText('UI_TW_ViewpointBallisticsLive'),true)
options:addTickBox('IsometricBallistics',getText('UI_TW_IsometricBallistics'),false,getText('UI_TW_IsometricBallistics_tooltip'))

-- The last options handed to Java ({on,live,mp}); reset() forgets them, so a new world hands them over again.
local pushed

-- The mod ballistics in the isometric view: in single player the client option IsometricBallistics; in MP the server
-- decides for every player (sandbox ViewpointBallistics 4, "Live: Viewpoint and isometric", W10 srvlive).
function M.isometric()
    if isClient()==true then
        local sandbox=SandboxVars and SandboxVars.EscapeFromZomboidFirearms
        return sandbox~=nil and sandbox.ViewpointBallistics==4
    end
    local isoOpt=options:getOption('IsometricBallistics')
    return isoOpt~=nil and isoOpt:getValue()==true
end

function M.options()
    if not (TWZB and TWZB.shotOptions) then return end
    local value=options:getOption('ViewpointBallistics'):getValue()
    if value~=1 and value~=2 and value~=3 then value=2 end -- an invalid stored value stays conservative
    if isClient()==true then
        -- Replicated server sandbox policy always owns MP, including no-VP peers.
        value=SandboxVars and SandboxVars.EscapeFromZomboidFirearms and SandboxVars.EscapeFromZomboidFirearms.ViewpointBallistics or 1
        if value~=1 and value~=2 and value~=3 and value~=4 then value=1 end
    end
    local isoOn=M.isometric()
    local V=rawget(_G,'TWViewpoint')
    local vpOn=V~=nil and V.enabled()
    local inIso=TWZB and TWZB.vpState and TWZB.vpState()==0
    local on,live
    if not isClient() and isoOn and (inIso or not vpOn) then
        on,live=true,true
    else
        on=value~=1 and (vpOn or isoOn)
        live=value==3 or value==4
    end
    local mp=isClient()==true
    -- Java only on a change (player report 23.10.2026: every tick); ShotOwner.options with the same values does nothing.
    if M.memoOff or not pushed or pushed[1]~=on or pushed[2]~=live or pushed[3]~=mp then
        TWZB.shotOptions(on,live,mp);pushed={on,live,mp}
    end
    return on
end

-- shot-alignment:start (generated and checked by tools/check_shot_owner.py)
-- Source assets/viewpoint_optics.json SHA256=a5679923d8f08a09b2ebeec5a8aceff08fed9317a79e9ae90b254e592d4adb20
-- Original supplemental datum: {}
-- Item-local PZ model units; the same mount datum, basis and scale as existing models.
local ALIGN_SOURCE_SHA256="a5679923d8f08a09b2ebeec5a8aceff08fed9317a79e9ae90b254e592d4adb20"
local ALIGN={}
ALIGN["54491c4f4bdc2db1078b4568"]={rear={5.223e-05,0.20214608,0.03295022}}
ALIGN["55801eed4bdc2d89578b4588"]={front={-0.00021965,0.52244901,0.03214234}}
ALIGN["5580223e4bdc2d1c128b457f"]={rear={3.745e-05,0.14396698,0.03232394}}
ALIGN["55d448594bdc2d8c2f8b4569"]={front={-9.65e-05,0.33590849,0.00756568}}
ALIGN["55d4491a4bdc2d882f8b456e"]={front={-9.65e-05,0.27734603,0.00756402}}
ALIGN["55d449444bdc2d962f8b456d"]={front={4.06e-06,0.34020466,0.0107197},rear={4.06e-06,0.00516991,0.01103433}}
ALIGN["55d4af3a4bdc2d972f8b456f"]={front={-5.584e-05,-0.0019623,0.02151639}}
ALIGN["55d5f46a4bdc2d1b198b4567"]={rear={1.092e-05,-0.01315108,0.02032813}}
ALIGN["560835c74bdc2dc8488b456f"]={front={4.06e-06,0.28171897,0.0107064},rear={4.06e-06,0.00516991,0.01103433}}
ALIGN["560836484bdc2d20478b456e"]={front={-9.65e-05,0.29471933,0.00756402}}
ALIGN["560836b64bdc2d57468b4567"]={front={4.06e-06,0.29896995,0.0107064},rear={4.06e-06,0.00516991,0.01103433}}
ALIGN["560836fb4bdc2d773f8b4569"]={front={-9.65e-05,0.33590849,0.00756568}}
ALIGN["560837154bdc2da74d8b4568"]={front={4.05e-06,0.36889565,0.01072783},rear={4.06e-06,0.00516991,0.01103433}}
ALIGN["5608373c4bdc2dc8488b4570"]={front={-9.651e-05,0.39338342,0.00756621}}
ALIGN["560837544bdc2de22e8b456e"]={front={4.05e-06,0.39758729,0.01072783},rear={4.06e-06,0.00516991,0.01103433}}
ALIGN["560837824bdc2d57468b4568"]={front={-9.651e-05,0.41646688,0.00756623}}
ALIGN["5608379a4bdc2d26448b4569"]={front={4.05e-06,0.42056192,0.01072783},rear={4.06e-06,0.00516991,0.01103433}}
ALIGN["56083e1b4bdc2dc8488b4572"]={rear={-2.209e-05,-0.00988657,0.00765168}}
ALIGN["5644bd2b4bdc2d3b4c8b4572"]={front={-7.481e-05,0.31237752,0.02904826}}
ALIGN["5649b0544bdc2d1b2b8b458a"]={rear={-5.772e-05,-0.02995035,0.00213795}}
ALIGN["5649d9a14bdc2d79388b4580"]={rear={-8.58e-06,0.00210998,0.00241931}}
ALIGN["56d5a661d2720bd8418b456b"]={front={4e-07,-0.00212047,0.00136453}}
ALIGN["56d5a77ed2720b90418b4568"]={rear={-5.49e-06,8.82e-06,0.00158571}}
ALIGN["56dee2bdd2720bc8328b4567"]={rear={4.897e-05,0.20288915,0.03290021}}
ALIGN["56deec93d2720bec348b4568"]={front={4.06e-06,0.4062957,0.01032385},rear={4.06e-06,0.00516991,0.01079289}}
ALIGN["571a12c42459771f627b58a0"]={front={-0.00107547,0.08482502,0.01356425},rear={-0.00107547,-0.00597306,0.01497371}}
ALIGN["574db213245977459a2f3f5d"]={rear={9e-08,-0.02758033,0.00230658}}
ALIGN["576165642459773c7a400233"]={front={-1.989e-05,0.21973048,0.02521941}}
ALIGN["576a581d2459771e7b1bc4f1"]={front={-4.964e-05,0.08967274,0.01825503},rear={-4.964e-05,-0.00779695,0.01787561}}
ALIGN["57838e1b2459774a256959b1"]={rear={2.634e-05,-0.0321875,0.00245491}}
ALIGN["57a9b9ce2459770ee926038d"]={rear={4.37e-05,-0.00070414,0.00348051}}
ALIGN["57c44e7b2459772d28133248"]={rear={2.634e-05,-0.0321875,0.00245491}}
ALIGN["57d14d2524597714373db789"]={front={8.013e-05,0.08731103,0.02970531},rear={2.66e-06,-0.0383638,0.03003111}}
ALIGN["57dc2fa62459775949412633"]={front={-4.717e-05,0.18715945,0.03137054}}
ALIGN["57dc334d245977597164366f"]={rear={-5.077e-05,-0.04226088,0.00450885}}
ALIGN["57f3c6bd24597738e730fa2f"]={front={8.013e-05,0.08731103,0.0297054},rear={2.66e-06,-0.0383638,0.0300312}}
ALIGN["57f4c844245977379d5c14d1"]={front={8.013e-05,0.08731103,0.0297054},rear={2.66e-06,-0.0383638,0.0300312}}
ALIGN["583990e32459771419544dd2"]={front={-4.717e-05,0.18715953,0.03137054}}
ALIGN["5839a40f24597726f856b511"]={front={-4.717e-05,0.18715953,0.03137054}}
ALIGN["5839a7742459773cf9693481"]={rear={-1.15e-06,-0.04745376,0.00644256}}
ALIGN["588200af24597742fa221dfb"]={front={4.06e-06,0.32993126,0.01032385},rear={4.06e-06,0.00516991,0.01079289}}
ALIGN["588200c224597743990da9ed"]={front={4.06e-06,0.35792278,0.01032385},rear={4.06e-06,0.00516991,0.01079289}}
ALIGN["588200cf2459774414733d55"]={front={4.06e-06,0.38515423,0.01032385},rear={4.06e-06,0.00516991,0.01079289}}
ALIGN["5894a73486f77426d259076c"]={front={-6.155e-05,-0.01046438,0.01960954}}
ALIGN["5894a81786f77427140b8347"]={rear={-5.102e-05,-0.00984984,0.01953483}}
ALIGN["5926d2be86f774134d668e4e"]={rear={-1.458e-05,0.0045012,0.00521661}}
ALIGN["59984ab886f7743e98271174"]={front={7.41e-06,0.19718345,0.02799881}}
ALIGN["599860e986f7743bb57573a6"]={rear={-1.371e-05,-0.0262168,0.00221734}}
ALIGN["59d6088586f774275f37482f"]={front={1.316e-05,0.31403486,0.02950564}}
ALIGN["59d650cf86f7741b846413a4"]={rear={-1.371e-05,-0.0298839,0.00267811}}
ALIGN["59e6152586f77473dc057aa1"]={front={1.316e-05,0.31403486,0.02950564}}
ALIGN["59e6687d86f77411d949b251"]={front={1.316e-05,0.31403486,0.02950564}}
ALIGN["59e8977386f77415a553c453"]={rear={-1.371e-05,-0.0298839,0.00267811}}
ALIGN["59f98b4986f7746f546d2cef"]={front={-7.119e-05,0.15451461,0.02073977},rear={-7.119e-05,0.05581658,0.0207336}}
ALIGN["59f9cabd86f7743a10721f46"]={front={7.41e-06,0.19718345,0.02799881}}
ALIGN["59ff346386f77477562ff5e2"]={front={1.316e-05,0.39373165,0.02950564}}
ALIGN["5a0eb980fcdbcb001a3b00a6"]={rear={-5.772e-05,-0.0324833,0.00359222}}
ALIGN["5a0ec13bfcdbcb00165aa685"]={front={1.316e-05,0.31403486,0.02950564}}
ALIGN["5a0ed824fcdbcb0176308b0d"]={rear={-8.61e-06,-0.00269043,0.00392605}}
ALIGN["5a0f096dfcdbcb0176308b15"]={front={0.0,-0.00288793,0.00132085}}
ALIGN["5a38e6bac4a2826c6e06d79b"]={front={-4.124e-05,0.33165885,0.0347517},rear={-4.124e-05,0.17965945,0.03475329}}
ALIGN["5a6f58f68dc32e000a311390"]={front={0.0,-0.00068289,0.00219486}}
ALIGN["5a6f5d528dc32e00094b97d9"]={rear={-2e-08,-0.00017233,0.00222885}}
ALIGN["5a71e0048dc32e000c52ecc8"]={front={0.0,-0.00440236,0.00461228}}
ALIGN["5a71e0fb8dc32e00094b97f2"]={rear={0.0,-0.00134697,0.00463293}}
ALIGN["5a7828548dc32e5a9c28b516"]={rear={1.941e-05,0.12442939,0.03223706}}
ALIGN["5a787ebcc5856700142fdd98"]={front={-0.00011839,0.26275517,0.01587666},rear={-0.00011839,0.07989278,0.01659565}}
ALIGN["5a787f25c5856700186c4ab9"]={front={-0.000145,0.19712695,0.01026572}}
ALIGN["5a787f7ac5856700177af660"]={front={-7.916e-05,0.27563449,0.01026572}}
ALIGN["5a787fadc5856700155a6ca1"]={front={-0.00016479,0.36091961,0.00939865},rear={-0.00016479,0.01011412,0.01241112}}
ALIGN["5a78948ec5856700177b1124"]={rear={-4.294e-05,-0.02572627,0.00522839}}
ALIGN["5a7d90eb159bd400165484f1"]={front={0.0,-0.00564885,0.00272898}}
ALIGN["5a7d9104159bd400134c8c21"]={front={0.0,-0.01295684,0.00311321}}
ALIGN["5a7d9122159bd4001438dbf4"]={rear={2e-08,-0.00074828,0.00349257}}
ALIGN["5a7d912f159bd400165484f3"]={rear={0.0,-0.00503333,0.00294511}}
ALIGN["5aafa49ae5b5b00015042a58"]={front={-1.26e-06,-3.148e-05,0.01006858}}
ALIGN["5ab8e9fcd8ce870019439434"]={front={-8.941e-05,0.31303489,0.02905196}}
ALIGN["5aba62f8d8ce87001943946b"]={front={0.0,0.00196722,0.00374105}}
ALIGN["5aba637ad8ce87001773e17f"]={rear={-1.458e-05,-0.02327731,0.00374109}}
ALIGN["5aba639ed8ce8700182ece67"]={rear={-1.31e-06,-0.02327731,0.00373788}}
ALIGN["5abcbb20d8ce87001773e258"]={rear={-1e-08,-0.00151689,0.00776395}}
ALIGN["5abcbc27d8ce8700182eceeb"]={front={1.316e-05,0.39373165,0.02950564}}
ALIGN["5ac4cd105acfc40016339859"]={front={-8.51e-05,0.31237328,0.02905602}}
ALIGN["5ac66cb05acfc40198510a10"]={front={-8.815e-05,0.31237773,0.02904949}}
ALIGN["5ac66d015acfc400180ae6e4"]={front={-8.815e-05,0.25249832,0.02904949}}
ALIGN["5ac66d2e5acfc43b321d4b53"]={front={-8.815e-05,0.31237773,0.02904949}}
ALIGN["5ac66d725acfc43b321d4b60"]={front={-8.815e-05,0.25250184,0.02904949}}
ALIGN["5ac66d9b5acfc4001633997a"]={front={-8.815e-05,0.25249932,0.02904949}}
ALIGN["5ac72e475acfc400180ae6fe"]={rear={-5.772e-05,-0.02993777,0.00216942}}
ALIGN["5ac733a45acfc400192630e2"]={rear={-8.358e-05,-0.03037101,0.00216942}}
ALIGN["5addba3e5acfc4001669f0ab"]={front={-1.26e-06,0.0001238,0.009988}}
ALIGN["5ae099875acfc4001714e593"]={front={0.0,5.595e-05,0.00799872}}
ALIGN["5ae099925acfc4001a5fc7b3"]={rear={-9.8e-07,-0.04672364,0.00408282}}
ALIGN["5ae30bad5acfc400185c2dc4"]={rear={-1e-08,-0.01486773,0.02032973}}
ALIGN["5b0bc22d5acfc47a8607f085"]={rear={9.77e-06,0.00488509,0.00512494}}
ALIGN["5b3b713c5acfc4330140bd8d"]={front={-0.00107544,0.08482499,0.01356424},rear={-0.00107544,-0.00597309,0.01497371}}
ALIGN["5ba26b01d4351e0085325a51"]={front={-1.027e-05,-0.00487339,0.01960006}}
ALIGN["5ba26b17d4351e00367f9bdd"]={rear={5.51e-06,-0.00029493,0.01971366}}
ALIGN["5bb20e49d4351e3bac1212de"]={rear={-2.7e-07,-0.00296538,0.01871208}}
ALIGN["5bc09a18d4351e003562b68e"]={rear={-1e-08,-0.01694659,0.01882257}}
ALIGN["5bc09a30d4351e00367fb7c8"]={front={0.0,-0.01378836,0.01888721}}
ALIGN["5bf3e03b0db834001d2c4a9c"]={front={-7.646e-05,0.31237724,0.02905011}}
ALIGN["5bf3e0490db83400196199af"]={front={-8.767e-05,0.31302719,0.02904861}}
ALIGN["5bf3f59f0db834001a6fa060"]={rear={-2.428e-05,-0.02958183,0.00286198}}
ALIGN["5bfd4c980db834001b73449d"]={rear={-2.38e-06,-0.03354832,0.00554958}}
ALIGN["5c05293e0db83400232fff80"]={front={0.0,0.02154614,0.03798167}}
ALIGN["5c05295e0db834001a66acbb"]={rear={-1.04e-06,-7.7e-07,0.01549443}}
ALIGN["5c052a900db834001a66acbd"]={front={0.0,0.02154614,0.03798167}}
ALIGN["5c07b36c0db834002a1259e9"]={front={-0.00010055,-0.00337783,0.00374998}}
ALIGN["5c07b3850db834002330045b"]={rear={-0.00011256,-0.00766026,0.00350349}}
ALIGN["5c1780312e221602b66cc189"]={rear={-1e-08,-0.01415728,0.01954455}}
ALIGN["5c17804b2e2216152006c02f"]={front={0.0,-0.00815556,0.0185036}}
ALIGN["5c18b90d2e2216152142466b"]={front={0.0,-0.01378836,0.01888721}}
ALIGN["5c18b9192e2216398b5a8104"]={rear={-1e-08,-0.01694659,0.01882257}}
ALIGN["5c471b7e2e2216152006e46c"]={rear={-1.248e-05,-0.03496506,0.00353552}}
ALIGN["5c471ba12e221602b3137d76"]={front={0.0,0.00014482,0.00665119}}
ALIGN["5c501a4d2e221602b412b540"]={front={0.01266561,0.40423235,0.03697159}}
ALIGN["5c503b1c2e221602b21d6e9d"]={rear={1.6e-07,-0.0289975,0.0014902}}
ALIGN["5cadd919ae921500126a77f3"]={front={0.0,-0.00270124,0.00186128}}
ALIGN["5cadd940ae9215051e1c2316"]={rear={-2e-08,-0.00026701,0.00188869}}
ALIGN["5cadd954ae921500103bb3c2"]={rear={-2e-08,-0.00435017,0.00156465}}
ALIGN["5caf1691ae92152ac412efb9"]={rear={-0.00011305,-0.06599757,0.02756254}}
ALIGN["5caf16a2ae92152ac412efbc"]={front={-1.23e-06,-0.00291749,0.02756352}}
ALIGN["5cc70102e4a949035e43ba74"]={front={0.00978554,0.06300927,0.04690702},rear={0.00978554,-0.01342402,0.04690703}}
ALIGN["5cf7acfcd7f00c1084477cf2"]={front={0.00978554,0.06300927,0.04690702},rear={0.00978554,-0.01342402,0.04690703}}
ALIGN["5d3eb4aba4b93650d64e497d"]={rear={-1.224e-05,-0.00621807,0.00709445}}
ALIGN["5d3eb536a4b9363b1f22f8e2"]={front={-1.002e-05,8.3e-07,0.00713808}}
ALIGN["5de65547883dde217541644b"]={front={-5.407e-05,0.32250527,0.01402913},rear={-5.405e-05,0.02427877,0.01405213}}
ALIGN["5de8fb539f98ac2bc659513a"]={rear={0.0,-0.00352945,0.00380798}}
ALIGN["5dfa3d7ac41b2312ea33362a"]={rear={-6.098e-05,-0.00912509,0.01882423}}
ALIGN["5dfa3d950dee1b22f862eae0"]={front={-1.313e-05,-0.00989806,0.01888863}}
ALIGN["5e81ee213397a21db957f6a6"]={front={0.0,2.415e-05,0.00151473}}
ALIGN["5e81ee4dcb2b95385c177582"]={rear={5e-08,-0.00044294,0.00366251}}
ALIGN["5e848cc2988a8701445df1e8"]={rear={-1e-08,0.16479615,0.03245552}}
ALIGN["5e8708d4ae379e67d22e0102"]={front={-3e-08,0.00462121,0.01024819}}
ALIGN["5e87114fe2db31558c75a120"]={rear={-8.02e-06,-0.03690313,0.00776398}}
ALIGN["5ea03e5009aa976f2e7a514b"]={front={-8.9e-07,0.12363001,0.02520635},rear={-8.9e-07,-0.0896327,0.02694392}}
ALIGN["5f3e7897ddc4f03b010e204a"]={rear={5e-08,-0.00764725,0.00636111}}
ALIGN["5f3e78a7fbf956000b716b8e"]={front={0.0,-0.00340212,0.00349254}}
ALIGN["5fb6564947ce63734e3fa1da"]={rear={-1e-08,-0.0068473,0.02067613}}
ALIGN["5fb6567747ce63734e3fa1dc"]={front={-0.00074084,-0.01183895,0.01791465}}
ALIGN["5fc0fa362770a0045c59c677"]={front={-0.00035282,-0.0074576,0.02135172}}
ALIGN["5fc0fa957283c4046c58147e"]={rear={6.21e-06,-0.01054072,0.02125817}}
ALIGN["5fc3e272f8b6a877a729eac5"]={front={-1.8e-07,0.24812796,0.02683886},rear={-2.6e-07,0.05699664,0.02683886}}
ALIGN["60228a76d62c9b14ed777a66"]={front={-4e-08,-0.00346931,0.0028642}}
ALIGN["60228a850ddce744014caf69"]={front={-4e-08,-0.00264335,0.00603469}}
ALIGN["602293f023506e50807090cb"]={rear={-2e-08,0.0001249,0.00648298}}
ALIGN["60229948cacb6b0506369e27"]={rear={-2e-08,-0.00061205,0.0031197}}
ALIGN["60db29ce99594040e04c4a27"]={rear={-1e-08,0.16555059,0.0344626}}
ALIGN["61816fcad92c473c770215cc"]={front={0.0,0.0001281,0.02892261}}
ALIGN["61817865d3a39d50044c13a4"]={rear={0.0,-0.01360973,0.02131897}}
ALIGN["6194f2912d2c397d6600348d"]={rear={5e-08,-0.00238081,0.00245063}}
ALIGN["6194f2df645b5d229654ad77"]={rear={5e-08,-0.00858668,0.00501781}}
ALIGN["6194f3286db0f2477964e67d"]={front={0.0,2.415e-05,0.00501769}}
ALIGN["6194f35c18a3974e5e7421e6"]={front={0.0,2.415e-05,0.00245058}}
ALIGN["619f4cee4c58466fe1228435"]={rear={4e-08,-0.00677063,0.00174866}}
ALIGN["619f4d304c58466fe1228437"]={front={1.3e-07,0.00338963,0.00221979}}
ALIGN["619f4f8c4c58466fe1228439"]={rear={4e-08,-0.00677063,0.00174866}}
ALIGN["619f52454c58466fe122843b"]={front={1.3e-07,-0.00149857,0.00220439}}
ALIGN["61f4012adfc9f01a816adda1"]={front={-5.59e-06,0.3021554,0.02690139},rear={-5.04e-06,0.07848632,0.02712233}}
ALIGN["622b3d5cf9cfc87d675d2de9"]={front={3.7e-07,0.12501105,0.01663467},rear={3.7e-07,-0.05579127,0.01663467}}
ALIGN["622b4d7df9cfc87d675d2ded"]={front={3.5e-07,0.09845672,0.02635352},rear={3.5e-07,0.01835365,0.02648708}}
ALIGN["622b4f54dc8dcc0ba8742f85"]={front={3.5e-07,0.09845672,0.02635352},rear={3.5e-07,0.01835365,0.02648708}}
ALIGN["623166e08c43374ca1567195"]={front={4e-08,-0.00152462,0.00811838}}
ALIGN["6231670f0b8aa5472d060095"]={rear={3e-08,0.00441577,0.01534814}}
ALIGN["624c2e8614da335f1e034d8c"]={rear={-0.00106082,0.00198207,0.02768934}}
ALIGN["625ebcef6f53af4aa66b44dc"]={rear={3e-08,-0.00804038,0.00701422}}
ALIGN["628a60ae6b1d481ff772e9c8"]={front={-7.771e-05,0.23799087,0.0297398}}
ALIGN["628a7b23b0f75035732dd565"]={rear={0.0,-0.0304207,0.00307637}}
ALIGN["628b5638ad252a16da6dd245"]={front={-7.76e-05,0.3116135,0.02832989}}
ALIGN["628b9471078f94059a4b9bfb"]={rear={8.644e-05,-0.0035514,0.00490236}}
ALIGN["628b9c37a733087d0d7fe84b"]={front={-7.76e-05,0.24997269,0.02835759}}
ALIGN["62ea7c793043d74a0306e19f"]={front={-0.00116328,-0.01650744,0.06405279},rear={-0.00116234,-0.13108381,0.06416926}}
ALIGN["630765777d50ff5e8a1ea718"]={front={0.0,-0.00419125,0.00224162}}
ALIGN["630765cb962d0247b029dc45"]={rear={-2e-08,-0.00115493,0.00263345}}
ALIGN["633ec7c2a6918cb895019c6c"]={front={-0.00065674,0.18350356,0.04369282},rear={-0.00065674,0.0185048,0.04369282}}
ALIGN["63c6adcfb4ba094317063742"]={rear={-1.002e-05,-0.00051135,0.002629}}
ALIGN["64119d672c6d6f921a0929fb"]={front={4e-08,2e-08,0.00708336}}
ALIGN["64119d90dcf48d656f0aa275"]={rear={-1.224e-05,-0.03378681,0.00264905}}
ALIGN["645122f6d4928d46d30be3ff"]={front={0.0,0.07692363,0.02067563},rear={0.0,0.00209928,0.02067563}}
ALIGN["645123013d52156624001fd1"]={front={0.0,0.07692363,0.02067563},rear={0.0,0.00548783,0.021319}}
ALIGN["64748d02d1c009260702b526"]={front={4.092e-05,0.1394431,0.02605843},rear={4.092e-05,-0.033754,0.02605843}}
ALIGN["6492fb8253acae0af00a29b6"]={rear={-1.59e-06,0.03621531,0.00765788}}
ALIGN["6499849fc93611967b034949"]={front={-2.596e-05,0.25225418,0.03197847}}
ALIGN["649ec2cec93611967b03495e"]={rear={1.6e-07,-0.02960572,0.00229151}}
ALIGN["6513f153e63f29908d0ffaba"]={rear={-3.3e-07,-0.0344375,0.00378383}}
ALIGN["651450ce0e00edc794068371"]={front={0.00033837,0.17379573,0.02583837},rear={0.00033837,0.08595516,0.02584243}}
ALIGN["655cb6b5d680a544f30607fa"]={rear={9.34e-06,0.00053458,0.00524452}}
ALIGN["6601265f98a610c1aa0ea637"]={rear={0.00018901,-0.00261256,0.0373966}}
ALIGN["6601268bc752a02bbe05e686"]={front={-3.052e-05,0.00251257,0.04914062}}
ALIGN["6680304edadb7aa61d00cef0"]={front={-0.0003841,0.12083567,0.0275813},rear={-0.0003841,0.01891743,0.0275813}}
ALIGN["668e71a8dadf42204c032ce1"]={front={-0.0003841,0.12083567,0.0275813},rear={-0.0003841,0.01891743,0.0275813}}
ALIGN["668fe5e1800f0244f9036e46"]={rear={-5e-08,-0.00329827,0.00699417}}
ALIGN["668fe5ec4315934ba10c6f96"]={front={-3e-08,0.00132984,0.00515501}}
ALIGN["66992b349950f5f4cd06029f"]={front={-0.00039376,0.12281089,0.03434221},rear={-0.00039376,-0.06161127,0.03434221}}
ALIGN["66ffa9b66e19cc902401c5e8"]={front={3e-08,0.331876,0.05104486},rear={2e-08,0.05917437,0.05533669}}
ALIGN["674fe8cf4472d471fb0f07df"]={rear={-0.00010778,-0.00815698,0.0097829}}
ALIGN["674fe8dd362ea1f88b0e2792"]={front={0.0,0.00095234,0.00696537}}
ALIGN["674fe9a75e51f1c47c04ec23"]={front={-1.989e-05,0.21973048,0.02521941}}
ALIGN["676bf44c5539167c3603e869"]={front={-0.00021962,0.208355,0.09281356},rear={-0.00021962,0.05171337,0.09552954}}
ALIGN["5ae30e795acfc408fb139a0b"]={front={-3.037e-05,0.01827494,0.04164976}}
-- shot-alignment:end

local function launcher(path)
    for slot in path:gmatch('[^/]+') do if slot=='mod_launcher' then return true end end
    return false
end

local function barrelPath(tree)
    local selected
    for path,tpl in pairs(tree) do
        local item=C.data.items[tpl]
        if not launcher(path) and item and item.category=='Barrel' then
            if selected then return nil,false end -- No proven active barrel selector for this assembly.
            selected=path
        end
    end
    return selected,true
end

-- The active carrier barrel and its muzzle devices only; no launcher fireport,
-- and no default root fireport left ahead of an installed shorter barrel.
function M.muzzle(key,tree)
    local barrel,known=barrelPath(tree)
    if not known then return end
    local best=not barrel and C.data.items[C.root(key)].muzzle or nil
    for path,tpl in pairs(tree) do
        local item=C.data.items[tpl]
        local active=not barrel or path==barrel or path:sub(1,#barrel+1)==barrel..'/'
        if active and not launcher(path) and item and item.muzzle then
            local place=C.placement(key,tree,path)
            local point=place and C.transformPoint(place,item.muzzle)
            if point and (not best or point[2]>best[2]) then best=point end
        end
    end
    -- Break-action barrels without their own fireport (MP-43) fire from the weapon's root/carrier muzzle.
    if not best then best=C.data.items[C.root(key)].muzzle or (C.data.weapons[key] and C.data.weapons[key].muzzle) end
    return best and C.toRender(key,best) or nil
end

function M.bore(key,tree)
    local barrel,known=barrelPath(tree)
    if not known then return end
    local frame=barrel and C.placement(key,tree,barrel) or C.identity
    if not frame then return end
    return C.toRender(key,{frame[1][2],frame[2][2],frame[3][2]}),
        C.toRender(key,{frame[1][3],frame[2][3],frame[3][3]})
end

local function placed(key,tree,path,p)
    local matrix=path=='' and C.identity or C.placement(key,tree,path)
    return matrix and C.toRender(key,C.transformPoint(matrix,p)) or nil
end

-- Original Tarkov alignment points, matched to the selected physical rear channel.
function M.alignment(key,tree,view)
    if not view then return end
    local ia=view.ironAlignment
    if ia then
        if ia.status=='aligned' and ia.rear and ia.front and ia.rear.position and ia.front.position then
            return C.toRender(key,ia.rear.position),C.toRender(key,ia.front.position)
        end
        return
    end
    local rear,front,onlyRear,rearCount
    rearCount=0
    local paths={''}
    for path in pairs(tree) do if not launcher(path) then paths[#paths+1]=path end end
    table.sort(paths)
    for _,path in ipairs(paths) do
        local tpl=path=='' and C.root(key) or tree[path]
        local row=ALIGN[tpl]
        if row then
            if row.rear then
                local p=placed(key,tree,path,row.rear)
                if p then rearCount=rearCount+1;onlyRear=p end
                if path==(view.path or '') then rear=p end
            end
            if row.front then
                local p=placed(key,tree,path,row.front)
                if p and (not front or p[2]>front[2]) then front=p end
            end
        end
    end
    -- A built-in channel has no selected rear item path. A unique installed
    -- physical rear is proven; multiple independent rear datums are ambiguous.
    if not rear and (view.path or '')=='' and rearCount==1 then rear=onlyRear end
    return rear,front
end

-- The sight frame (3x4) a request was built from, copied: compared by value, so a frame changed in place counts too.
local function copyFrame(m)
    if type(m)~='table' then return m end
    local out={}
    for i=1,3 do local row=m[i] or {};for j=1,4 do out[(i-1)*4+j]=row[j] end end
    return out
end
local function sameFrame(flat,m)
    if type(flat)~='table' or type(m)~='table' then return flat==m end
    for i=1,3 do
        local row=m[i]
        if type(row)~='table' then return false end
        for j=1,4 do if flat[(i-1)*4+j]~=row[j] then return false end end
    end
    return true
end

function M.params(player,gun)
    -- The same identity the dedicated server arms for this weapon (TW/ShotIdentity).
    local id,reason=Identity.read(gun)
    if not id then return nil,reason end
    local key,tree=id.key,id.tree
    -- Muzzle and bore follow from the tree alone: kept while the snapshot's tree is the same table.
    local g=M.geometry[gun]
    if not g or g.tree~=tree or g.key~=key then
        local bore,up=M.bore(key,tree)
        g={tree=tree,key=key,muzzle=M.muzzle(key,tree),bore=bore,up=up};M.geometry[gun]=g
    end
    local muzzle,bore,up=g.muzzle,g.bore,g.up
    if not muzzle or not bore then return nil,'muzzle-or-bore-unproven' end
    local view=Sight.current(player)
    local V=rawget(_G,'TWViewpoint')
    local path,channel=view and (view.path or '') or '',view and view.channel
    local ownerId=view and (view.part or gun):getID() or gun:getID()
    local mode,zero=view and view.mode or 0,view and view.zeroDistance or 0
    local aiming=(TWZB and TWZB.aimIntent or player.isAiming)(player)
    local isometric=M.isometric()
    local allowed,vtb,sttActive=V~=nil and V.enabled(),V~=nil and V.vtb(),V~=nil and V.stt()
    local stt=sttActive and (V.giveToStt() or gun:getModData().ISeeThem_DisabledComponent~=true)
    local sightFrame=view and TWFirstPersonPose and TWFirstPersonPose.sightFrame
    local frame,kind=view and view.frame,view and view.kind
    local ironSig=view and view.ironAlignment and view.ironAlignment.signature or nil
    -- Player report 23.10.2026 (0.72 ms every tick with a weapon in hand): the request, its sight camera and the iron
    -- alignment (a sort and a placement per part) were rebuilt every tick. Now only when an input changes: the identity
    -- (the snapshot's tree and parts), the geometry, each value below and the sight frame by value. Java re-reads
    -- the same table every tick and so keeps its stamp fresh.
    local c=not M.memoOff and M.memo
    if c and c.gun==gun and c.id==id and c.muzzle==muzzle and c.bore==bore and c.up==up and c.view==(view~=nil)
        and c.path==path and c.channel==channel and c.ownerId==ownerId and c.mode==mode and c.zero==zero
        and c.aiming==aiming and c.allowed==allowed and c.isometric==isometric and c.vtb==vtb and c.sttActive==sttActive and c.stt==stt
        and c.sightFrame==sightFrame and c.kind==kind and c.ironSig==ironSig and sameFrame(c.frame,frame) then
        return c.t
    end
    local t={key=key,revision=id.revision,geometryHash=ALIGN_SOURCE_SHA256,parts=id.parts,muzzle=muzzle,
        bore=bore,up=up,
        channel=view and (path..':'..tostring(channel)) or 'hip',
        path=path,channelIndex=view and channel or -1,
        ownerId=ownerId,mode=mode,zero=zero,aiming=aiming,
        allowed=allowed,isometric=isometric,vtb=vtb,sttActive=sttActive,stt=stt}
    if sightFrame then
        t.sight=sightFrame(key,tree,view)
        t.kind=kind
        t.rear,t.front=M.alignment(key,tree,view)
    end
    M.memo={t=t,gun=gun,id=id,muzzle=muzzle,bore=bore,up=up,view=view~=nil,path=path,channel=channel,ownerId=ownerId,
        mode=mode,zero=zero,aiming=aiming,allowed=allowed,isometric=isometric,vtb=vtb,sttActive=sttActive,stt=stt,sightFrame=sightFrame,
        kind=kind,ironSig=ironSig,frame=copyFrame(frame)}
    return t
end

function M.update(player)
    if not (TWZB and TWZB.shotSet) then return end
    local gun=player and player:getPrimaryHandItem()
    local ok,params,reason=pcall(M.params,player,gun)
    if not ok then
        M.errors=M.errors+1;M.lastError=tostring(params)
        if TWZB.shotFail then TWZB.shotFail(M.lastError) end
    end
    TWZB.shotSet(player,ok and params and gun or nil,ok and params or nil)
    if ok and not params and TWZB.shotUnavailable then TWZB.shotUnavailable(reason or 'params-unavailable') end
    M.sent=ok and params~=nil
end

-- Diagnostic only. Zero never changes the visible pose or the PIP camera.
function M.zero(player,gun)
    if not (TWZB and TWZB.shotZero) then return end
    local t=M.params(player,gun)
    return t and TWZB.shotZero(player,gun,t) or nil
end

Events.OnTick.Add(function()
    if not (TWZB and TWZB.shotTick) then return end
    local on=M.options()
    local p=getSpecificPlayer(0)
    if on and p and p:isLocalPlayer() and not p:isDead() then M.update(p)
    elseif M.sent then TWZB.shotSet(nil,nil,nil);M.sent=false end
    TWZB.shotTick()
end)
local function reset()
    if TWZB and TWZB.shotReset then TWZB.shotReset() end
    M.sent=false;M.memo=nil;pushed=nil
end
Events.OnGameStart.Add(function() reset();M.options() end)
if Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(reset) end
if Events.OnDisconnect then Events.OnDisconnect.Add(reset) end
function M.stats() return TWZB and TWZB.shotInfo and TWZB.shotInfo() or 'Java part absent' end
return M
