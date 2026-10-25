(function(){var els=window.__gh.els;var slot=els[11];var orig=slot.cloneNode(true);
var other=els[7].cloneNode(true);slot.replaceWith(other);
var res=document.querySelector('.results')||document.body;res.appendChild(orig);
return 'mutated: slot now '+other.tagName+'/'+(other.innerText||'').trim().slice(0,30);})()