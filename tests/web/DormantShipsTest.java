package dev.diorama.web.vehicles;

import java.util.*;

public final class DormantShipsTest {
    record Ship(String key,String dimension,int pose) implements DormantShips.Snapshot {}
    public static void main(String[] args) {
        DormantShips state=new DormantShips();
        Ship ship=new Ship("sable_ships/one","overworld",42);
        Map<String,Ship> cache=new LinkedHashMap<>();cache.put(ship.key(),ship);
        List<Ship> rows=new ArrayList<>();
        state.append("overworld",rows,cache,64);
        check(rows.isEmpty(),"an unexplained disappearance became a permanent ghost");
        state.event("overworld",ship.key(),"UNLOADED");
        state.append("overworld",rows,cache,64);
        check(rows.equals(List.of(ship)),"unloading removed the last geometry/pose snapshot");
        rows.clear();state.append("nether",rows,cache,64);
        check(rows.isEmpty(),"unloaded ship leaked into another dimension");
        Ship live=new Ship(ship.key(),"overworld",99);rows.add(live);
        state.append("overworld",rows,cache,64);
        check(rows.equals(List.of(live)),"dormant pose replaced or duplicated live pose");
        state.event("overworld",ship.key(),"REMOVED");rows.clear();state.append("overworld",rows,cache,64);
        check(rows.isEmpty(),"deleted ship survived as a ghost");
        state.event("overworld",ship.key(),"UNLOADED");state.event("overworld",ship.key(),"LOADED");
        state.append("overworld",rows,cache,64);check(rows.isEmpty(),"reload left stale dormant state");
        state.event("overworld",ship.key(),"UNLOADED");rows.add(live);state.append("overworld",rows,cache,1);
        check(rows.size()==1,"retained ships exceeded publication budget");
        state.retain(Set.of());check(!state.contains(ship.key()),"evicted snapshot left an unbounded lifecycle entry");
        System.out.println("Dormant ship unload/reload/removal, last-pose, dimension and capacity checks passed");
    }
    static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}
