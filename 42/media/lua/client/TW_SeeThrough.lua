-- See-through weapon and hands in Viewpoint's first person (FB3, the user's item 4, 18.10.2026):
-- an optional switch for a semi-transparent, blurred weapon and hands while aiming and in High
-- Ready, the front sight of iron sights kept in focus while aiming (SeeThrough.java). One opacity
-- for the whole model - the weapon, its sight and the hands (the user's follow-ups the same day);
-- only a sight's reticle or mark stays opaque. This module owns the options and tells the Java part
-- the sight of the moment: for irons the front sight's part (its mesh, the post's apex and width in
-- the part's own units: kept opaque, the post sharp), else the active optic's or collimator's part
-- (faded like the rest; its reticle is drawn apart). No pose, sight or item state is written.
require('TW_Tactical')
local C=require('TW/Core')
local R=require('TW/Runtime')
local Sight=require('TW/SightState')
local A=require('TW/Data/SightChannels')
local G=require('TW/GeometryState')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addTickBox('SeeThroughWeapon',getText('UI_TW_SeeThroughWeapon'),false,getText('UI_TW_SeeThroughWeapon_tooltip'))
options:addSlider('SeeThroughOpacity',getText('UI_TW_SeeThroughOpacity'),10,90,5,50)
options:addSlider('SeeThroughBlur',getText('UI_TW_SeeThroughBlur'),0,10,1,5)

local M={selection=nil,sent=nil,errors=0,lastError=''}
TWSeeThrough=M

-- The mesh Viewpoint draws for a part: its tpl, or its geometry state's mesh.
function M.mesh(gun,tpl,path)
    if path=='' then return 'TW/'..tpl end
    local part=R.parts(gun)[path]
    if not part then return nil end
    local row={tpl=tpl,path=path,part=part,fullType=part:getFullType()}
    G.annotate(R.key(gun),R.tree(gun),{row})
    return 'TW/'..(row.state and ('States/'..tpl..'_'..row.state) or tpl)
end

-- {front, x, y, z, width, sight} for the view: irons name the front sight's part and post; an
-- optic, a collimator or a sensor sight names its own part.
function M.sight(gun,view)
    local key=R.key(gun)
    if not key or not view then return nil end
    if view.kind=='iron' or view.kind=='aim' then
        local alignment=view.ironAlignment
        local front=alignment and alignment.visualStatus=='verified' and alignment.front
        local entry=front and A.alignment[front.tpl] and A.alignment[front.tpl].front
        local visual=entry and entry.visual
        if not (visual and visual.position) then return nil end
        local p=visual.position
        -- Root points follow the exported root's roll; part-local points do not.
        if front.path=='' and C.renderRoll(key) then p={-p[1],p[2],-p[3]} end
        local mesh=M.mesh(gun,front.tpl,front.path)
        if not mesh then return nil end
        return {front=mesh,x=p[1],y=p[2],z=p[3],width=visual.width or 0,sight=''}
    end
    if view.tpl then
        local mesh=M.mesh(gun,view.tpl,view.path or '')
        if mesh then return {front='',x=0,y=0,z=0,width=0,sight=mesh} end
    end
    return nil
end

function M.enabled()
    return options:getOption('SeeThroughWeapon'):getValue()==true
end

local NONE={front='',x=0,y=0,z=0,width=0,sight=''}

function M.update(player)
    local gun=player and player:getPrimaryHandItem()
    local key=gun and R.key(gun)
    -- Native DoF needs the same verified front post independently of translucency.
    local dof=rawget(_G,'FpDepthOfField')
    local keepSight=M.enabled() or (dof and dof.needsSight and dof.needsSight())
    local view=key and keepSight and Sight.current(player) or nil
    local selection=view and table.concat({tostring(gun:getID()),tostring(view.path),tostring(view.tpl),
        tostring(view.channel),tostring(view.mode),tostring(view.kind),
        tostring(view.ironAlignment and view.ironAlignment.signature)},':') or ''
    if selection==M.selection then return end
    M.selection=selection
    local s=view and M.sight(gun,view) or NONE
    M.sent=s
    TWZB.seeThroughSight(s.front,s.x,s.y,s.z,s.width,s.sight)
end

Events.OnTickEvenPaused.Add(function()
    if not (TWZB and TWZB.seeThroughOptions and TWZB.seeThroughSight) then return end
    local ok,err=pcall(function()
        TWZB.seeThroughOptions(M.enabled(),options:getOption('SeeThroughOpacity'):getValue()/100,
            options:getOption('SeeThroughBlur'):getValue())
        M.update(getSpecificPlayer(0))
    end)
    if not ok then M.errors=M.errors+1;M.lastError=tostring(err) end
end)

function M.stats()
    return TWZB and TWZB.seeThroughStats and TWZB.seeThroughStats() or 'Java part absent'
end

return M
