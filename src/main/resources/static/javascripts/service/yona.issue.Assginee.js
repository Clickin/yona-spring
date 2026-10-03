/**
 * Yona, 21st Century Project Hosting SW
 * <p>
 * Copyright Yona Authors & NAVER Corp. & NAVER LABS Corp.
 * https://yona.io
 **/
// #assignee는 data-toggle="tomselect" 자동 초기화 대상이 아니라 이 모듈이 직접 TomSelect를
// 생성하므로, change 이벤트 브릿지도 여기서 직접 걸어야 한다(evt.val을 읽는 다른 코드와의
// 호환을 위해 - yona.ui.TomSelect.js 상단 주석 참고).
function yonaAssgineeModule(findAssignableUsersApiUrl, updateAssgineesApiUrl, message, root){
  var lifecycle = new AbortController();
  var MIN_INPUT_LENGTH = 0;
  var resultCache = {};

  function formatter(data, escape){
    var name = data.name || data.text || data.loginId;
    if(!data.avatarUrl){
      return "<div>" + escape(name) + "</div>";
    }

    var loginId = data.loginId ? "@" + data.loginId : "";

    return '<div class="usf-group" title="' + escape(name) + ' ' + escape(loginId) + '">' +
      '<span class="avatar-wrap smaller"><img src="' + escape(data.avatarUrl) + '" width="20" height="20"></span>' +
      '<strong class="name">' + escape(name) + '</strong>' +
      '<span class="loginid">' + escape(loginId) + '</span>' +
      '</div>';
  }

  function score(search){
    var term = search.toLowerCase();
    return function(item){
      var text = (item.name || item.text || "").toString().toLowerCase();
      var loginId = (item.loginId || "").toString().toLowerCase();
      return (loginId.indexOf(term) > -1 || text.indexOf(term) > -1) ? 1 : 0;
    };
  }

  var assigneeElement = (root || document).querySelector("#assignee");

  var tomSelectInstance = new TomSelect(assigneeElement, {
    valueField: "loginId",
    labelField: "name",
    searchField: ["name", "loginId"],
    maxItems: null,
    plugins: ['remove_button'],
    highlight: false,
    score: score,
    loadThrottle: 300, // select2 ajax.quietMillis:300 대응
    // minimumInputLength:0 대응 - 검색어가 비어 있어도(길이 0) 항상 load를 허용한다.
    shouldLoad: function(query){ return query.length >= MIN_INPUT_LENGTH; },
    load: function(query, callback){
      if(resultCache.hasOwnProperty(query)){ // select2 ajax.cache:true 대응
        callback(resultCache[query]);
        return;
      }

      fetch(findAssignableUsersApiUrl + "?" + new URLSearchParams({ query: query }), {signal: lifecycle.signal})
        .then(function(response){
          if(!response.ok){
            return Promise.reject(response);
          }
          return response.json();
        })
        .then(function(data){
          if(lifecycle.signal.aborted){ return; }
          resultCache[query] = data || [];
          callback(resultCache[query]);
        })
        .catch(function(){
          if(lifecycle.signal.aborted){ return; }
          callback();
        });
    },
    render: {
      option: formatter,
      item: formatter,
      not_loading: function(data){
        var n = MIN_INPUT_LENGTH - data.input.length;
        return n > 0 ? '<div class="no-results">' + yona.ui.TomSelect.i18n.tooShort(n) + '</div>' : '';
      },
      no_results: function(){ return '<div class="no-results">' + yona.ui.TomSelect.i18n.noResults + '</div>'; },
      loading: function(){ return '<div class="no-results">' + yona.ui.TomSelect.i18n.searching + '</div>'; }
    }
  });

  yona.ui.TomSelect.bridgeChangeEvent(tomSelectInstance, assigneeElement);

  var savedItems = tomSelectInstance.items.slice();
  var clearButton = (root || document).querySelector('[data-clear-assignees]');
  var saving = false;

  function update(assignees, action){
    if(!updateAssgineesApiUrl){ return; }
    saving = true;
    tomSelectInstance.lock();
    if(clearButton){ clearButton.disabled = true; }
    fetch(updateAssgineesApiUrl, {
      signal: lifecycle.signal,
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({assignees: assignees, action: action})
    })
    .then(function(response){
      if(!response.ok){ throw new Error(response.statusText); }
      return response.json();
    })
    .then(function(response){
      if(lifecycle.signal.aborted){ return; }
      response.assignees.forEach(function(user){ tomSelectInstance.addOption(user); });
      savedItems = response.assignees.map(function(user){ return user.loginId; });
      tomSelectInstance.setValue(savedItems, true);
      $yona.notify(message + ": " + response.assignees.map(function(user){ return user.name; }).join(", "), 3000);
    })
    .catch(function(error){
      if(lifecycle.signal.aborted){ return; }
      tomSelectInstance.setValue(savedItems, true);
      alert(message + ": " + error.message);
    })
    .finally(function(){
      if(lifecycle.signal.aborted){ return; }
      saving = false;
      tomSelectInstance.unlock();
      if(clearButton){ clearButton.disabled = false; }
    });
  }

  tomSelectInstance.on("item_add", function(value){
    if(!saving){ update([value], "toggle"); }
  });
  tomSelectInstance.on("item_remove", function(value){
    if(!saving){ update([value], "toggle"); }
  });
  if(clearButton){
    clearButton.addEventListener("click", function(){
      if(saving){ return; }
      if(updateAssgineesApiUrl){ update([], "clear"); }
      else { tomSelectInstance.clear(true); }
    }, {signal: lifecycle.signal});
  }
  return function(){
    lifecycle.abort();
    tomSelectInstance.destroy();
  };
}
