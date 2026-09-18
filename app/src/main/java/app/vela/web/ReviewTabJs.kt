package app.vela.web

/**
 * `velaNoName(label)` for the two review scripts: Google's tab and button labels carry the PLACE
 * NAME ("Overview of Davis Food Co-op", 「X」總覽), and the review-word pattern holds the word in
 * every language, so a name containing one of them ("D-avis" matches the French "avis") made the
 * Overview tab read as the Reviews tab. The full review page then reported ready on the Overview
 * and its More reviews button reloaded back to it (issue #535). The name is cut out before any
 * label is tested: the page heading when there is one, else the longest run shared by two tab
 * labels (every labeled tab repeats the name).
 */
internal const val STRIP_PLACE_NAME_JS = """
function velaPlaceName(){
  var h=document.querySelector('h1'); var n=h&&(h.textContent||'').trim();
  if(n&&n.length>=2) return n;
  var ls=[].slice.call(document.querySelectorAll('[role="tab"]')).map(function(t){ return ((t.getAttribute('aria-label')||t.textContent)||'').trim(); }).sort(function(a,b){ return b.length-a.length; });
  if(ls.length<2) return '';
  var a=ls[0], b=ls[1], best='';
  for(var i=0;i<a.length;i++){ for(var j=i+best.length+1;j<=a.length;j++){ var s=a.slice(i,j); if(b.indexOf(s)>=0){ best=s; } else break; } }
  best=best.trim();
  return best.length>=4 ? best : '';
}
function velaNoName(t){ t=t||''; var n=velaPlaceName(); return n ? t.split(n).join(' ') : t; }
"""
