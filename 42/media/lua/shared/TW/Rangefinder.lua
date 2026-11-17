-- Vanilla Tarkov rangefinders. State belongs to TW/Tactical, never the network.
local C=require('TW/Core')
local T=require('TW/Tactical')
local D=require('TW/RangefinderData')
local F={data=D}

function F.devices(key,tree,parts)
    local out={}
    for _,d in ipairs(T.devices(tree,parts)) do
        local item=D.items[d.tpl]
        local state=T.state(d.part,d.tpl)
        local mode=item and state.on and item.modes[state.mode]
        local placement=mode and C.placement(key,tree,d.path)
        if placement then
            out[#out+1]={path=d.path,part=d.part,mode=mode,modeIndex=state.mode,tpl=d.tpl,
                placement=placement,position=C.transformPoint(placement,mode.position),maxDistance=mode.range}
        end
    end
    return out
end

-- Unity Mathf.RoundToInt uses nearest-even at an exact half metre.
function F.round(distance)
    local n=math.floor(distance)
    local f=distance-n
    return f>.5 and n+1 or f<.5 and n or (n%2==0 and n or n+1)
end

function F.measure(result,mode)
    local ray=result and result.ray
    local distance=ray and result.distance-(ray.begin or 0)-(mode.offset or 0)
    if not result or not result.hit or not distance or distance<0 or distance>mode.range
        or result.reason=='unloaded' or result.reason=='range' then
        return {text=mode.noReturn,status=result and result.reason or 'unavailable'}
    end
    return {metres=distance,text=string.format('%04d',F.round(distance)),status='return',
        x=result.x,y=result.y,z=result.z,kind=result.reason}
end

-- Original TMP glyph metrics and display transform; 4 equal character cells.
-- Coordinates are root model space, through the same placement as the device.
function F.vertices(key,device,text)
    local mode=device.mode
    local size=mode.fontSize/mode.fontPointSize
    local advance=D.glyphs['0'].advance*size+mode.characterSpacing
    local baseline=-(D.fontAscent+D.fontDescent)*size*.5
    local out={}
    local italic=D.italic
    for i=1,#text do
        local g=D.glyphs[text:sub(i,i)]
        if g then
            local left=(i-1-#text*.5)*advance+(advance-g.advance*size)*.5+g.bearingX*size
            local top=baseline+g.bearingY*size
            for _,p in ipairs({{0,0,1,2},{0,1,1,4},{1,1,3,4},{0,0,1,2},{1,1,3,4},{1,0,3,2}}) do
                local x,y=left+p[1]*g.width*size,top-p[2]*g.height*size
                local v=C.transformPoint(device.placement,C.transformPoint(mode.display,{x+y*italic,y,0}))
                v=C.toRender(key,v)
                out[#out+1],out[#out+2],out[#out+3],out[#out+4],out[#out+5]=v[1],v[2],v[3],g.uv[p[3]],g.uv[p[4]]
            end
        end
    end
    return out
end

-- The model carries the off mode's display panel (FUnC); a rangefinder mode shows its own
-- panel there, an empty LCD under the digits. Its triangles, through the same placement.
function F.panel(key,device)
    local item=D.items[device.tpl]
    local v=item and item.panel and item.panel.vertices
    if not v then return nil end
    local out={}
    for i=1,#v,5 do
        local p=C.toRender(key,C.transformPoint(device.placement,{v[i],v[i+1],v[i+2]}))
        out[#out+1],out[#out+2],out[#out+3],out[#out+4],out[#out+5]=p[1],p[2],p[3],v[i+3],v[i+4]
    end
    return out
end
return F
