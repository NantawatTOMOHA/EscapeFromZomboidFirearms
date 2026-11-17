-- Tarkov muzzle effects of a shot: flash, smoke, sparks, barrel smoke trail and
-- the muzzle light, in place of the vanilla flash (Java part, TWZB.muzzle*).
-- The values are the Tarkov prefab data (TW/Data/MuzzleFx, tools/muzzle_fx.py).
-- Here, as Tarkov's MuzzleManager.UpdateJetsAndFumes chooses them: the effect
-- nodes (GameObjects with a MuzzleJet/MuzzleFume) that lie deepest in the
-- weapon hierarchy - the last muzzle part: the barrel, a flash hider or brake
-- on it, a suppressor on that, or the weapon's own default muzzle - plus the
-- extractor smoke; nodes under mod_launcher play only for the launcher
-- (underbarrel mode: MuzzleManager.LauncherShot, fumes only). Depth counts
-- transforms: each part sits under its slot's transform in the parent prefab
-- (slot depths of the data). Nodes are placed in the root model space of the
-- exported model (C.placement, C.renderMatrix) and sent per item whenever its
-- assembly is rendered (Render.onApplied), with the assembly's hot objects and
-- overheat factors (M.heat: the barrel and parts heat up with firing). Gunworks
-- explosions (ExplosionFX) get the Tarkov explosion light and heat (TWZB.explosion).
local C=require('TW/Core')
local Render=require('TW/Render')
local ok,FX=pcall(require,'TW/Data/MuzzleFx')
if not ok then FX=nil end
local M={data=FX,sent=false,stats={setups=0,nodes=0,missingDepth=0,errors=0,explosions=0}}

local function java()
    local zb=rawget(_G,'TWZB')
    if type(zb)=='table' and zb.muzzleSetup then return zb end
end

local function matrix(f)
    return {{f[1],f[2],f[3],f[4]},{f[5],f[6],f[7],f[8]},{f[9],f[10],f[11],f[12]}}
end

local function flat(m)
    return {m[1][1],m[1][2],m[1][3],m[1][4],m[2][1],m[2][2],m[2][3],m[2][4],m[3][1],m[3][2],m[3][3],m[3][4]}
end

-- 3x4 affine product a*b.
local function mul(a,b)
    local r={}
    for i=1,3 do
        r[i]={}
        for j=1,4 do
            local v=a[i][1]*b[1][j]+a[i][2]*b[2][j]+a[i][3]*b[3][j]
            if j==4 then v=v+a[i][4] end
            r[i][j]=v
        end
    end
    return r
end
M.mul=mul

-- Depth of a slot's transform in its item's prefab (from the weapon transform
-- for the root). Unknown slots count as a direct child.
function M.slotDepth(tpl,slot)
    local e=FX.items[tpl]
    local d=e and e.sd and e.sd[slot]
    if d==nil then
        M.stats.missingDepth=M.stats.missingDepth+1
        return 1
    end
    return d
end

-- Candidate effect nodes of an assembly with their transform depth: the root's
-- own and every installed part's, in a stable path order.
function M.candidates(key,tree)
    local root=C.root(key)
    local list={}
    local function add(tpl,path,base,launcher,placement)
        local e=FX.items[tpl]
        if not (e and e.n) then return end
        for _,n in ipairs(e.n) do
            list[#list+1]={node=n,depth=base+n.d,launcher=launcher,placement=placement,path=path,tpl=tpl}
        end
    end
    add(root,'',0,false,C.identity)
    local paths={}
    for path in pairs(tree) do paths[#paths+1]=path end
    table.sort(paths)
    for _,path in ipairs(paths) do
        local depth,parent,current,launcher,valid=0,root,'',false,true
        for slot in path:gmatch('[^/]+') do
            depth=depth+M.slotDepth(parent,slot)+1
            if slot=='mod_launcher' then launcher=true end
            current=current=='' and slot or current..'/'..slot
            parent=tree[current]
            if not parent then valid=false;break end
        end
        local placement=valid and C.placement(key,tree,path)
        if placement then add(tree[path],path,depth,launcher,placement) end
    end
    return list
end

-- MuzzleManager.UpdateJetsAndFumes: the deepest MuzzleEffect nodes outside
-- mod_launcher and the extractor smoke (the last one found) for a shot; the
-- fumes under mod_launcher for a launcher shot.
function M.select(list)
    local deepest
    for _,c in ipairs(list) do
        if not c.launcher and not c.node.x and (not deepest or c.depth>deepest) then deepest=c.depth end
    end
    local shot,launcher,extractor={},{},nil
    for _,c in ipairs(list) do
        if c.launcher then
            if c.node.fume then launcher[#launcher+1]=c end
        elseif c.node.x then extractor=c
        elseif c.depth==deepest then shot[#shot+1]=c end
    end
    if extractor then shot[#shot+1]=extractor end
    return shot,launcher
end

function M.nodes(key,tree)
    return M.select(M.candidates(key,tree))
end

-- A node in the root model space of the exported model, for the Java part; a
-- launcher shot plays only its fumes (MuzzleManager.LauncherShot).
function M.entry(key,c,fumesOnly)
    local n=c.node
    local m=C.renderMatrix(key,mul(c.placement,matrix(n.m)))
    if fumesOnly then return {m=flat(m),fume=n.fume} end
    return {m=flat(m),jet=n.jet,fume=n.fume,sparks=n.sparks,smoke=n.smoke,x=n.x==true,pivot=n.pivot}
end

-- Barrel and part heat (Tarkov WeaponPrefab.InitHotObjects, Weapon.GetShotOverheat /
-- GetCurrentOverheat): every HotObject of the assembly (root and parts) takes the
-- weapon's temperature; the heat and cooling factors of every installed part
-- multiply (the round's on each shot, in the Java part). Nil when the weapon does
-- not overheat (AllowOverheat). Each hot object also carries its glow on the
-- model (q: the box matrix in the mesh space of its item, u: its own sub-mesh's
-- atlas rectangle, t: that item's template, the mesh TW/<t>) and its lit zone
-- along the bore (a, b node-local,
-- radius r) for the thermal image; the data holds only hot objects whose
-- bounds light their own mesh (tools/muzzle_fx.py hot_geometry).
function M.heat(key,tree)
    local root=C.root(key)
    local w=FX.weapons[root]
    local h=w and w.heat
    if not h or not FX.overheat then return nil end
    local shot=h.shot*h.gun*FX.overheat.modHeat
    local mods=h.mods
    local hot={}
    local function row(n,m,tpl)
        local pr=FX.hot.profiles[n.k]
        return {m=flat(m),c=n.c,e=n.e,v=n.v,c1=pr.c1,c2=pr.c2,s=pr.spec,a=n.a,b=n.b,r=n.r,q=n.q,u=n.u,t=tpl,name=n.name}
    end
    local function add(tpl,placement)
        local e=FX.items[tpl]
        if not e then return end
        if e.hf then shot=shot*e.hf end
        if e.cf then mods=mods*e.cf end
        if placement and e.hot then
            for _,n in ipairs(e.hot) do
                hot[#hot+1]=row(n,C.renderMatrix(key,mul(placement,matrix(n.m))),tpl)
            end
        end
    end
    local e=FX.items[root]
    if e and e.hot then
        for _,n in ipairs(e.hot) do
            hot[#hot+1]=row(n,C.renderMatrix(key,matrix(n.m)),root)
        end
    end
    local paths={}
    for path in pairs(tree) do paths[#paths+1]=path end
    table.sort(paths)
    for _,path in ipairs(paths) do add(tree[path],C.placement(key,tree,path)) end
    return {shot=shot,cool=h.cool,mods=mods*FX.overheat.modCool,hot=hot}
end

function M.ensureData(zb)
    if M.sent or not FX then return end
    zb.muzzleData(FX)
    M.sent=true
    -- New data: every item takes its nodes again (see M.signature).
    M.applied=setmetatable({},{__mode='k'})
end

-- Performance pass 23.10.2026 (reload spikes): a reload re-applies the render 2-4 times with the same
-- assembly, and each time the nodes and heat were placed again (C.placement of every part) and the Java
-- part made a new setup. They depend only on the parts whose data carries effect nodes, hot objects or
-- heat/cool factors (n, hot, hf, cf) and on the parts above those (their frames and slot depths place
-- them): a magazine or any other part without them and with no such part under it changes nothing. That
-- signature keys one computed result, and an item whose signature did not change is not sent again.
M.cache,M.cached,M.applied={},0,setmetatable({},{__mode='k'})
M.stats.reused=0

local function shaping(tpl)
    local e=FX.items[tpl]
    return e~=nil and (e.n~=nil or e.hot~=nil or e.hf~=nil or e.cf~=nil)
end

function M.signature(key,tree,launcher)
    local keep={}
    for path,tpl in pairs(tree) do
        -- a path already kept is an ancestor of a shaping part: its own ancestors are kept too
        if not keep[path] and shaping(tpl) then
            local current=''
            for slot in path:gmatch('[^/]+') do
                current=current=='' and slot or current..'/'..slot
                keep[current]=true
            end
        end
    end
    local rows={}
    for path in pairs(keep) do rows[#rows+1]=path end
    table.sort(rows)
    -- an absent ancestor ("nil") stops its parts' placement: it is part of the signature too
    for i,path in ipairs(rows) do rows[i]=path..'='..tostring(tree[path]) end
    return tostring(key)..'|'..tostring(launcher)..'|'..table.concat(rows,';')
end

-- The Java part's inputs of an assembly: the node entries and the heat (M.heat).
function M.compute(key,tree,launcher)
    local shot,fumes=M.nodes(key,tree)
    local list={}
    for _,c in ipairs(launcher and fumes or shot) do list[#list+1]=M.entry(key,c,launcher) end
    return {list=list,heat=M.heat(key,tree)}
end

function M.isLauncher(weapon)
    local L=rawget(_G,'TWLaunchers')
    return L~=nil and L.hostKey~=nil and L.hostKey(weapon)~=nil
end

-- Render.onApplied: the assembly of an item is rendered, its effect nodes go to the Java part.
function M.setup(weapon,key,tree)
    local zb=java()
    if not zb or not FX or not weapon or not key or not tree then return end
    local ok,err=pcall(function()
        M.ensureData(zb)
        local launcher=M.isLauncher(weapon)
        local sig=M.signature(key,tree,launcher)
        if M.applied[weapon]==sig then M.stats.reused=M.stats.reused+1;return end
        local r=M.cache[sig]
        if not r then
            r=M.compute(key,tree,launcher)
            if M.cached>=64 then M.cache,M.cached={},0 end
            M.cache[sig]=r;M.cached=M.cached+1
        end
        zb.muzzleSetup(weapon,C.root(key),r.list,launcher)
        if zb.muzzleHeat then zb.muzzleHeat(weapon,r.heat) end
        M.applied[weapon]=sig
        M.stats.setups=M.stats.setups+1
        M.stats.nodes=M.stats.nodes+#r.list
    end)
    if not ok then
        M.stats.errors=M.stats.errors+1
        if M.lastError~=tostring(err) then M.lastError=tostring(err);print('[TW Muzzle] setup error: '..M.lastError) end
    end
end

Render.onApplied=M.setup

local ExplosionApi

-- Gunworks ExplosionFX (the launcher rounds of this mod and other Gunworks
-- explosives): the explosion's square and offset as it draws its frames.
function M.explosion(square,lx,ly,lz,itemType)
    local zb=java()
    if not zb or not zb.explosion or not square then return end
    M.ensureData(zb)
    local x,y,z=square:getX()+(lx or .5),square:getY()+(ly or .5),square:getZ()+(lz or 0)
    local handle
    if itemType=='EscapeFromZomboidFirearms.FX_' then
        -- Gunworks' visual event does not carry the source round template.
        -- Preserve that unknown; all current launcher rounds use this effect.
        -- (performance pass 23.10.2026: required once; TW/ExplosionFx requires this module back lazily)
        ExplosionApi=ExplosionApi or require('TW/ExplosionFx')
        handle=ExplosionApi.explode({x=x,y=y,z=z,kind='launcher',effect='smallgrenade_expl',sourceTpl='',
            seed=(math.floor(x*1000)*73856093+math.floor(y*1000)*19349663+math.floor(z*1000)*83492791)%2147483647})
    end
    if not handle then zb.explosion(x,y,z) end
    M.stats.explosions=M.stats.explosions+1
end

function M.hookExplosions()
    local okFX,ExplosionFX=pcall(require,'ExplosivesSystems/ExplosionFX')
    if not okFX or type(ExplosionFX)~='table' or type(ExplosionFX.PlayEffect)~='function' or ExplosionFX.TWMuzzle then return false end
    local play=ExplosionFX.PlayEffect
    local function wrapped(square,itemType,lx,ly,lz,duration,frames)
        local result=play(square,itemType,lx,ly,lz,duration,frames)
        local okBlast,err=pcall(M.explosion,square,lx,ly,lz,itemType)
        if not okBlast then print('[TW Muzzle] explosion error: '..tostring(err)) end
        return result
    end
    ExplosionFX.PlayEffect=wrapped
    ExplosionFX.TWMuzzle=true
    -- The singleplayer path calls the reference Gunworks exported at load.
    local okES,ES=pcall(require,'ExplosivesSystems/Init')
    if okES and type(ES)=='table' and ES.PlayExplosionFXLocal==play then ES.PlayExplosionFXLocal=wrapped end
    return true
end

Events.OnGameStart.Add(M.hookExplosions)
-- The user's choice (23.10.2026): the mod's GL programs compiled when the world starts (zb/ShaderWarmup), not at the
-- first shot, ADS, beam or night vision.
Events.OnGameStart.Add(function() if TWZB and TWZB.warmShaders then TWZB.warmShaders() end end)

return M
