-- The assembly identity of a TW firearm for the shot owner: weapon key, assembly revision and the
-- installed parts. The shooter's client (TW_ShotOwner) and the dedicated server (TW_ShotOwnerNet)
-- derive it with this one function from the same native items, so a live MP shot is admitted only
-- when both name the same assembly. Reads only; no item, mod data or network change.
local R=require('TW/Runtime')
local M={memo=setmetatable({},{__mode='k'})}
TWShotIdentity=M

-- key, revision, parts {path,tpl,id,fullType,part}, entries, tree; or nil, reason.
function M.read(gun)
    local key=R.key(gun)
    if not key then return nil,gun and 'weapon-unsupported' or 'no-weapon' end -- Launchers stay native.
    -- From the shared read-only snapshot (R.view; a fresh read where it is unavailable, as on the server): the
    -- identity is rebuilt only when the snapshot changes (part IDs are part of its version). Performance pass 23.10.2026.
    local tree,entries,_,problem,shared=(R.view or R.read)(gun)
    if problem then return nil,'assembly-'..tostring(problem) end
    local memo=M.memo[gun]
    if memo and memo.entries==entries and memo.key==key then return memo.id end
    local parts,signature={},{key}
    for _,entry in ipairs(entries) do
        local part=entry.part
        parts[#parts+1]={path=entry.path,tpl=entry.tpl,id=part:getID(),fullType=part:getFullType()}
        signature[#signature+1]=entry.path..'='..entry.tpl..'@'..tostring(part:getID())
    end
    local id={key=key,revision=table.concat(signature,';'),parts=parts,tree=tree,entries=entries}
    if shared then M.memo[gun]={entries=entries,key=key,id=id} end
    return id
end
return M
