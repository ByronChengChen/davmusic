package com.byron.davmusic;

/** Android-free identity used by queue lookup and reordering. */
public final class TrackIdentity {
    private TrackIdentity() {}
    public static boolean same(WebDAVFile a, WebDAVFile b) {
        if (a == null || b == null) return false;
        String sa = a.getServerId(), sb = b.getServerId();
        boolean ownedA = sa != null && !sa.isEmpty(), ownedB = sb != null && !sb.isEmpty();
        if ((ownedA || ownedB) && !java.util.Objects.equals(sa, sb)) return false;
        if (ownedA && a.getRelativePath() != null && b.getRelativePath() != null)
            return a.getRelativePath().equals(b.getRelativePath());
        String ha = a.getHref(), hb = b.getHref();
        if (ha != null && ha.equals(hb)) return true;
        String ra = a.getRelativePath(), rb = b.getRelativePath();
        return ra != null && ra.equals(rb);
    }
}
