-- Cosmetics only: no damage, projectile, ammunition or world mutation.
local profiles=require('TW/Data/ImpactProfiles')
local N={module='TWImpactMarks',profiles=profiles}
local outcomes={STOP=true,CONTINUE=true,RICOCHET=true,FRAGMENT=true,UNKNOWN_STOP=true}
local fields={'schema','id','preset','seed','x','y','z','nx','ny','nz','dx','dy','dz','outcome','forward','vehicle','sound','decal'}
function N.finite(n) return type(n)=='number' and n==n and n>-math.huge and n<math.huge end
function N.valid(row)
    if type(row)~='table' or row.schema~=1 or type(row.id)~='string' or #row.id<1 or #row.id>220
        or row.id:find('[%c]') or not profiles[row.preset] or not outcomes[row.outcome]
        or type(row.seed)~='string' or #row.seed>20 or not row.seed:match('^%-?%d+$')
        or type(row.forward)~='boolean' or type(row.vehicle)~='boolean' or type(row.sound)~='boolean'
        or (row.sound and not row.forward) or (row.decal~=nil and type(row.decal)~='boolean') then return false end
    for _,key in ipairs({'x','y','z','nx','ny','nz','dx','dy','dz'}) do if not N.finite(row[key]) then return false end end
    if math.abs(row.x)>1000000 or math.abs(row.y)>1000000 or row.z< -32 or row.z>64 then return false end
    for _,v in ipairs({{row.nx,row.ny,row.nz},{row.dx,row.dy,row.dz}}) do
        if math.abs(v[1]*v[1]+v[2]*v[2]+v[3]*v[3]-1)>.002 then return false end
    end
    return true
end
function N.copy(row)
    local out={};for _,key in ipairs(fields) do out[key]=row[key] end;return out
end
function N.call(name,...)
    local api=rawget(_G,'TWImpactFx')
    if type(api)~='table' or not api[name] then return nil end
    local ok,value=pcall(api[name],...)
    return ok and value or nil
end
-- Viewpoint may keep rendering FP after its optional TW bridge has failed.
-- Its public view selector prevents an isometric overlay over that FP image.
function N.isometric()
    local v=rawget(_G,'Viewpoint')
    if not v then return true end
    if not (v.View and v.View.next) then return false end
    local ok,value=pcall(v.View.next)
    return ok and tostring(value)=='first'
end
return N
