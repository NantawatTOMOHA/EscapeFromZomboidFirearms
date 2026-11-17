-- Assembly geometry only. All coordinates use the existing metre -> model
-- conversion in TW/Data; the returned prop points include the handgun roll.
local C=require('TW/Core')
local D=C.data
local L={cache={},order={},limit=256}
TWWeaponLength=L

function L.fingerprint(key,tree)
    local paths={}
    for path,tpl in pairs(tree or {}) do paths[#paths+1]=path..'='..tpl end
    table.sort(paths)
    return key..'#'..table.concat(paths,'|')
end

local function rearFace(bounds,matrix)
    local y=math.huge
    local lo,hi={math.huge,0,math.huge},{-math.huge,0,-math.huge}
    -- A rotated stock's rear-most vertices, rather than its unrotated bounds.
    local points={}
    for corner=0,7 do
        local p={}
        for a=1,3 do p[a]=math.floor(corner/2^(a-1))%2==0 and bounds.min[a] or bounds.max[a] end
        p=matrix and C.transformPoint(matrix,p) or p
        points[#points+1]=p
        y=math.min(y,p[2])
    end
    for _,p in ipairs(points) do
        if p[2]<=y+1e-6 then
            for _,a in ipairs({1,3}) do lo[a]=math.min(lo[a],p[a]);hi[a]=math.max(hi[a],p[a]) end
        end
    end
    return {(lo[1]+hi[1])/2,y,(lo[3]+hi[3])/2}
end

function L.assembly(key,tree)
    local weapon=D.weapons[key]
    if not weapon then return nil end
    tree=tree or {}
    local fingerprint=L.fingerprint(key,tree)
    if L.cache[fingerprint] then return L.cache[fingerprint] end
    local root=D.items[weapon.tpl]
    local rear=rearFace(root.bounds)
    local rearPath=''
    local hasMuzzle=root.muzzle~=nil
    -- Stocks, their pads/adapters and receiver pieces define the rear support.
    -- Magazines, sights and pistol grips must not lengthen a stockless receiver.
    for path,tpl in pairs(tree) do
        local item=D.items[tpl]
        if not item then return nil end
        if item.muzzle and C.placement(key,tree,path) then hasMuzzle=true end
        local support=item.category=='Stock' or item.category=='Receiver'
            or path:find('mod_stock',1,true)~=nil
        local matrix=support and C.placement(key,tree,path)
        if matrix and item.bounds then
            local p=rearFace(item.bounds,matrix)
            if p[2]<rear[2]-1e-7 or (math.abs(p[2]-rear[2])<1e-7 and path<rearPath) then
                rear,rearPath=p,path
            end
        end
    end
    -- C.muzzle's final fallback is the DEFAULT preset's marker; it must not
    -- give a stripped receiver a barrel/silencer which is no longer installed.
    local front=hasMuzzle and C.muzzle(key,tree) or nil
    local frontSource='muzzle'
    if not front then
        -- Incomplete assemblies can lack a barrel/fireport; use their actual
        -- front envelope, and expose that fallback to QA and the pose consumer.
        local b=C.bounds(key,tree)
        front={(b.min[1]+b.max[1])/2,b.max[2],(b.min[3]+b.max[3])/2}
        frontSource='bounds'
    end
    local length=front[2]-rear[2]
    if length<=0 then return nil end
    local result={key=key,fingerprint=fingerprint,front=front,rear=rear,
        frontProp=C.toRender(key,front),rearProp=C.toRender(key,rear),
        length=length,metres=length/D.modelScale,rearPath=rearPath,
        frontSource=frontSource,foldState='prefab',kind=weapon.kind}
    -- There is no fold/unfold transform or saved fold state in the current
    -- renderer. Measure its authored prefab state; never invent a shorter pose.
    if #L.order>=L.limit then L.cache[table.remove(L.order,1)]=nil end
    L.order[#L.order+1]=fingerprint
    L.cache[fingerprint]=result
    return result
end

-- TW/Runtime requires this file: loaded on first use and kept (a require per call scanned the game's list of loaded files).
local Runtime
L.held=setmetatable({},{__mode='k'})
function L.weapon(weapon)
    Runtime=Runtime or require('TW/Runtime')
    local R=Runtime
    local key=R.visualKey(weapon)
    if not key then return nil end
    -- The shared read-only snapshot (R.view): the same tree table until a part changes, so the same result.
    local tree,_,_,_,shared=(R.view or R.read)(weapon)
    local held=L.held[weapon]
    if shared and held and held.tree==tree and held.key==key then return held.result end
    local result=L.assembly(key,tree)
    if shared then L.held[weapon]={tree=tree,key=key,result=result} end
    return result
end

return L
