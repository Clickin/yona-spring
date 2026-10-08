/**
 * Yona, 21st Century Project Hosting SW
 * <p>
 * Copyright Yona Authors & NAVER Corp. & NAVER LABS Corp.
 * https://yona.io
 **/

window.yona = window.yona || {};
yona.initTasklist = function(root) {
    var markdownWraps = root.matches && root.matches(".markdown-wrap")
        ? [root] : root.querySelectorAll(".markdown-wrap");
    var inputCheckBox = "input[type='checkbox']";

    checkTasklistDoneCount(markdownWraps);
    disableCheckboxIfNeeds(markdownWraps);

    // 레거시 버그 보존: 원본이 closest()를 인자 없이 호출해 항상 빈 컬렉션이 되므로
    // 이 블록(부모 클릭/호버 시 체크박스 토글)은 원래부터 죽은 코드였다. 동작 유지를 위해 그대로 둔다.

    markdownWraps.forEach(function (wrap) {
        (wrap.shadowRoot || wrap).querySelectorAll(inputCheckBox).forEach(function (checkbox) {
            if (checkbox.yonaTasklistBound) return;
            checkbox.yonaTasklistBound = true;
            checkbox.addEventListener("click", function () {
                var form = wrap.closest("div[id]").previousElementSibling.querySelector("form");
                var url = form.getAttribute("action");
                var textarea = form.querySelector("textarea");
                var originalText = textarea.value;
                checkTask(checkbox);

                var text = textarea.value;

                NProgress.start();
                fetch(url, {
                    method: "PATCH",
                    headers: {"Content-Type": "application/json"},
                    body: JSON.stringify({ content: text, original: originalText })
                })
                .then(function(response){
                    if(!response.ok){
                        return response.text().then(function(text){
                            return Promise.reject({statusText: response.statusText, responseText: text});
                        });
                    }
                    return response.text();
                })
                .then(function (msg) {
                    NProgress.done();
                    if (wrap.matches("yona-markdown-renderer") && wrap.isConnected) {
                        var replacement = document.createElement("yona-markdown-renderer");
                        Array.prototype.forEach.call(wrap.attributes, function(attribute) {
                            replacement.setAttribute(attribute.name, attribute.value);
                        });
                        replacement.textContent = textarea.value;
                        wrap.replaceWith(replacement);
                    } else {
                        checkTasklistDoneCount(markdownWraps);
                    }
                })
                .catch(function(err){
                    var response = JSON.parse(err.responseText);
                    var message = '[' + err.statusText + '] ' + response.message + '\n\nRefresh the page!';
                    $yona.showAlert(message);
                    NProgress.done();
                });
            });
        });
    });

    function checkTask(that, checked) {
        var isChecked;
        if(checked === undefined) {
            isChecked = that.checked;
        } else {
            isChecked = checked;
        }

        that.checked = isChecked;

        var parent = that.getRootNode().host || that.closest(".markdown-wrap");
        var index = Array.prototype.indexOf.call((parent.shadowRoot || parent).querySelectorAll(inputCheckBox), that);
        var form = parent.closest("div[id]").previousElementSibling.querySelector("form");
        var textarea = form.querySelector("textarea");
        var text = textarea.value;

        var counter = 0;
        // See: https://regex101.com/r/uIC2RM/2
        text = text.replace(/^([ ]*[-+*] \[[ xX]?])([ ]?.+)/gm, function replacer(match, checkbox, text){
            var composedText = checkbox + text;
            if(index === counter) {
                if(isChecked) {
                    composedText = checkbox.replace(/\[[ ]?]/, "[x]") + text
                } else {
                    composedText = checkbox.replace(/\[[xX]?]/, "[ ]") + text
                }
            }
            counter++;
            return composedText;
        });

        textarea.value = text;
        if(that.nextElementSibling){
            that.nextElementSibling.querySelectorAll(inputCheckBox).forEach(function (checkbox) {
                checkTask(checkbox, isChecked);
            });
        }
    }

    function checkTasklistDoneCount(targets) {
        targets.forEach(function (target) {
            var total = 0;
            var checked = 0;
            (target.shadowRoot || target).querySelectorAll(inputCheckBox).forEach(function (checkbox) {
                total++;
                if(checkbox.checked) {
                    checked++;
                }
            });
            var tasklist = target.previousElementSibling;
            if(!tasklist || !tasklist.querySelector(".done-counter") ||
                !tasklist.querySelector(".bar") || !tasklist.querySelector(".task-title")){
                return;
            }
            var percentage = total ? checked / total * 100 : 0;
            tasklist.querySelector(".done-counter").innerHTML = "(" + checked + "/" + total + ")";
            tasklist.querySelector(".bar").style.width = percentage + "%";
            tasklist.querySelector(".task-title").style.width = percentage + "%";
            if(total > 0) {
                tasklist.classList.add("task-show");
            }
            if(percentage === 100) {
                tasklist.querySelector(".bar").classList.remove("red");
                tasklist.querySelector(".bar").classList.add("green");
            } else {
                tasklist.querySelector(".bar").classList.remove("green");
                tasklist.querySelector(".bar").classList.add("red");
            }
        });
    }

    function disableCheckboxIfNeeds(targets){
        targets.forEach(function (target) {
            var container = target.closest("div[id]");
            var editForm = container && container.previousElementSibling &&
                container.previousElementSibling.querySelector("form");
            var allowed = target.dataset.allowedUpdate === "true" && editForm &&
                editForm.querySelector("textarea") && editForm.getAttribute("action");
            (target.shadowRoot || target).querySelectorAll(inputCheckBox).forEach(function (checkbox) {
                checkbox.disabled = !allowed;
            });
        });
    }

    // See: addTaskListButtonListener() at views/common/scripts.scala.html
};

document.addEventListener("DOMContentLoaded", function() {
    yona.initTasklist(document);
});
